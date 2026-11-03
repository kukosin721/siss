# YouTube Music: de "no reproduce" a audio real

## El problema

`YtmService` (versión anterior) usaba la API de **Piped**:

```kotlin
streamUrl = "https://pipedapi.kavin.rocks/streams/$id"
```

Ese endpoint devuelve **JSON**, no audio, así que ExoPlayer fallaba siempre.
Y aunque se hubiera resuelto la URL, las instancias de Piped hoy devuelven
`"audioStreams": []` porque YouTube exige **po_token / SABR**: entregan
búsqueda y metadatos, pero no streams de audio.

Además la lista de "destacados" era contenido de ejemplo (Rick Astley con
`html5demos.com/dizzy.mp4`), así que al fallar la red la app mostraba canciones
falsas como si fueran resultados.

## La solución

**NewPipeExtractor** (`com.github.TeamNewPipe:NewPipeExtractor:v0.26.5`) resuelve
las URLs reales de audio **en el propio dispositivo** (sin servidor
intermediario, sin po_token). Es el mismo camino que usan Metrolist, Flow,
PipePipe, etc.

### Flujo nuevo

1. **Buscar** → `YoutubeMusicSearchExtractor` (filtro `music_songs`), hasta 40 canciones.
2. **Al tocar una canción** → `StreamInfo.getInfo()` resuelve las pistas de audio.
3. **Elegir la mejor pista** → mayor bitrate entre las URLs directas
   (`PROGRESSIVE_HTTP`), descartando manifiestos DASH/HLS que ExoPlayer no puede
   abrir sin módulos extra.
4. **Reproducir** → `MediaItem` con la URL real + metadata (título, artista, artwork).
5. **Caché** de 90 min por `videoId`: las URLs de googlevideo caducan, así que se
   reutilizan mientras son válidas y se vuelven a resolver cuando expiran.

## Archivos

| Archivo | Cambio |
|---|---|
| `data/ytm/YtmService.kt` | Reescrito: búsqueda YTM + resolución de audio + caché + mensajes de error en español |
| `data/ytm/NewPipeDownloader.kt` | **Nuevo**: `Downloader` de NewPipeExtractor sobre `HttpURLConnection` (sin agregar OkHttp) |
| `ui/LibraryViewModel.kt` | Estado de YTM: `ytmError`, `ytmResolvingId`, `ytmPlaying`, `ytmPlayingQuality`; `searchYtm()` con manejo de errores; `resolveYtmMediaItem()` |
| `ui/PlayerApp.kt` | `YtmView` con error visible, spinner por fila, tira de "reproduciendo" y click que resuelve el audio antes de reproducir |
| `app/build.gradle.kts` | Dependencia del extractor + core library desugaring |
| `gradle/libs.versions.toml` | `newpipeExtractor = "v0.26.5"`, `desugarJdkLibs = "2.1.5"` |
| `settings.gradle.kts` | Repositorio JitPack (grupo `com.github.TeamNewPipe`) |
| `app/src/main/keepRules/rules.keep` | Reglas R8 para el extractor, Rhino, nanojson, protobuf y jsoup |

## Bug adicional corregido

`LibraryViewModel` tenía **dos bloques `init`** y ambos llamaban a `searchYtm("")`:
dos peticiones de red al abrir la app. Ahora no se busca nada hasta que el
usuario escribe una consulta.

## Cómo probar

```bash
./gradlew assembleDebug   # sincroniza JitPack la primera vez
```

En la app: pestaña **YouTube Music** → escribir "bohemian rhapsody" → tocar el
botón de buscar (o la lupa del teclado) → tocar una canción. Debe aparecer el
spinner en la fila y después sonar, con la tira "reproduciendo" arriba.

## Verificación hecha

El código de integración (`YtmService` + `NewPipeDownloader`) se compiló y se
ejecutó contra el jar real de NewPipeExtractor v0.26.5:

```
resultados: 20
BSTsnWoslP4 | Bohemian Rhapsody | Queen | 5:55
...
codec=Opus bitrate=160kbps itag=251 mime=audio/webm
HTTP/1.1 206 Partial Content · Content-Type: audio/webm · archivo WebM válido
```

## Cosas a tener en cuenta

- **Licencia**: NewPipeExtractor es **GPL-3.0**. Si distribuyes la app, el
  proyecto completo tiene que publicarse bajo GPL-3.0.
- **minSdk 24** se mantiene gracias a core library desugaring. Si aparece algún
  `NoSuchMethodError` en dispositivos viejos, subir `minSdk` a 26 lo resuelve.
- **Tamaño**: el extractor arrastra Rhino, protobuf-javalite y jsoup (~2,5 MB
  antes de R8).
- **YouTube cambia seguido**: si un día la extracción falla, actualizar
  `newpipeExtractor` en `gradle/libs.versions.toml` (los releases salen cada
  1–2 meses).
- ~~El mini player muestra solo pistas locales~~ → resuelto: ver
  **"Streaming como cola real"** más abajo.

## Streaming como cola real (actualización)

Antes, tocar una canción de YTM hacía `setMediaItem()` con la URL ya resuelta:
un solo item en la cola. Consecuencias: **no había "siguiente"**, el botón de
avanzar no hacía nada y el gesto de carátulas rebotaba.

Ahora la cola es **toda la búsqueda** y cada stream se resuelve **cuando
ExoPlayer lo abre**:

```
MediaItem de YTM →  kukosin-ytm://stream/<videoId>
                         │
                         ▼  (PlaybackService)
   ResolvingDataSource.Factory(DefaultDataSource.Factory)
                         │
                         ▼  al hacer open(DataSpec)
   YtmService.resolveAudio(videoId)  →  https://…googlevideo.com/videoplayback…
```

- `ResolvingDataSource` solo reescribe las URIs con esquema `kukosin-ytm`;
  **cualquier otra URI pasa intacta**, así que la reproducción local no cambia.
- El contrato del resolver es síncrono (`runBlocking` en el hilo de carga de
  ExoPlayer), por eso `resolveAudio(videoId)` ahora acepta el id directamente.
- La caché de 90 min evita re-resolver al volver a una canción o al buscar
  dentro del mismo stream.
- Si la resolución falla, se lanza `IOException` con el mensaje ya traducido y
  el error se muestra en la pestaña de YouTube Music.

### Lo que eso habilita

| Antes | Ahora |
|---|---|
| 1 canción suelta | toda la búsqueda como cola |
| sin "siguiente" | `seekToNext()` / autoplay real |
| sin mini player | mini player + Now Playing con carátula y metadatos |
| gesto rebotando | el gesto de carátulas funciona igual que con archivos locales |

### Cómo la UI reconstruye la pista

`rememberPlaybackState` sigue funcionando con archivos locales (busca el `Track`
original por URI para tener todos los metadatos del archivo). Si el item actual
no es local, `MediaItem.toStreamTrack()` arma un `Track` "virtual" con
`artworkOverride` (thumbnail) y `streamCodecLabel`. `Track.isLocal` distingue
ambos casos y desactiva lo que no aplica:

- metadatos embebidos y análisis FFT (leen el archivo con MediaMetadataRetriever
  en el hilo principal: con una URL http congelarían la UI),
- el botón de análisis del sheet muestra en su lugar una explicación,
- las letras se marcan como no disponibles.
