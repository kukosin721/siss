# Arreglos de audio (medidos, no "debería andar")

## 1. Fuga de callbacks de dispositivo — `AudioRoute.kt`

`attach()` se llama desde `PlaybackService.recreatePlayer()`, o sea **cada vez que cambia un
ajuste de audio**. Adentro hacía:

```kotlin
audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() { … }, handler)
```

sin guardar la referencia ni desregistrarla nunca. Resultado: un listener más por cada cambio
de ajuste, todos ejecutando `scanDevices()` + `applyRoute()` en cada conexión/desconexión de
un dispositivo, y `applyRoute()` cambiando el `preferredAudioDevice` del player N veces por
evento.

**Arreglo:** el callback se registra una sola vez (guardado en `deviceCallback`) y se agrega
`AudioRoute.detach()`, que `PlaybackService.onDestroy()` llama al cerrarse.

## 2. Resampler nearest-neighbour → sinc con ventana — `SincResampler.kt` (nuevo)

`SampleRateAudioProcessor` tomaba **la muestra más cercana** para cambiar el sample rate: sin
interpolación y sin filtro anti-alias. Al bajar el sample rate (96 kHz → 44.1 kHz, el caso
típico de un FLAC hi-res en un DAC de 44.1), todo lo que está por encima de Nyquist se pliega
a la banda audible: los platillos suenan "arenosos" y aparece un tono fantasma.

**Arreglo:** filtro FIR sinc con ventana de Blackman (32 taps), tabla de 512 fases
precalculada, corte al 92 % del Nyquist de salida y estado persistente entre buffers
(índice entero + fracción, sin deriva numérica).

### Mediciones (JVM, `kotlinc`, mismas señales para los dos algoritmos)

| Prueba | Nearest (antes) | Sinc (ahora) | Mejora |
|---|---|---|---|
| Tono 1 kHz, 44.1 → 48 kHz: peor espuria en banda | −44.7 dBFS | **−98.5 dBFS** | **54 dB** |
| Tono 1 kHz, 44.1 → 48 kHz: S/N | 32.7 dB | **86.5 dB** | 54 dB |
| Tono 30 kHz, 96 → 44.1 kHz (debe desaparecer) | −9.0 dBFS (¡entero!) | **−88.9 dBFS** | **80 dB** |
| Alias en 14.1 kHz del mismo caso | −13.5 dBFS | **−91.9 dBFS** | **78 dB** |
| Continuidad entre buffers (4096 vs bloque único) | — | diferencia 0.000000000 | determinista |

La última fila importa: da **exactamente** el mismo resultado sin importar cómo media3 parta
los buffers. La primera versión (con posición en `double`) difería en ~1e-4 al cambiar el
tamaño de bloque; se ve en el código como índice entero + fracción.

Cómo se reproduce la medición: se compila `SincResampler.kt` solo (no tiene imports de
Android) con `kotlinc` y se corre un `main` que genera senos, remuestrea y mide con Goertzel/
DFT. Cualquiera puede re-correrlo sin teléfono.

## 3. Prefetch de YouTube Music — `PlaybackService.kt`

Resolver la URL de un tema tarda ~1.4 s en frío (medido). Como el resolver de media3 es
síncrono (`DataSpec` → `DataSpec`), eso se veía como silencio entre canciones.

**Arreglo:** al cambiar de tema, se resuelve en `Dispatchers.IO` el **siguiente** item de la
cola si es un stream de YTM, así la caché de 90 min de `YtmService` ya está llena cuando toca.
El `runBlocking` del resolver se queda porque es el contrato de media3, pero ahora casi nunca
encuentra la caché vacía.

## Otros (chicos)

- `AndroidManifest.xml`: fuera `android:usesCleartextTraffic="true"` — todo el tráfico es
  HTTPS (NewPipeExtractor, googlevideo), no hacía falta abrir HTTP en claro.
- `PlayerApp.kt`: los `!!` del sheet se cambiaron por locales capturados (`sheetController`,
  `sheetTrack`); `controller` es un `var` delegado y no admite smart cast.
- `AudioAnalyzer.kt`: `audio!!` → null-check explícito en los dos `when`.
- `QueueManager.toggleShuffle()`: ya no enciende el flag visual si la cola tiene 0 o 1 tema
  pendiente (antes quedaba "shuffle activo" sin haber reordenado nada).

## Lo que quedó pendiente (a propósito)

- **`QueueManager` O(n²)**: `applyPermutation` mueve de a un ítem con `moveMediaItem` (una
  llamada Binder cada uno). En colas de cientos de temas va a tironear. Hay que rediseñarlo
  para derivar el estado de etiquetas en el `MediaItem` en vez de `_customCount`.
- **Partir `PlayerApp.kt` (3.100 líneas) y `QueueSheet.kt` (1.486)**.
- **Tests**: hay material de sobra (`SincResampler` ya está probado numéricamente, pero fuera
  del repo; `QueueManager`, `Fft`, `Track.codecFrom` y `YtmService` con un `Downloader` falso
  son el siguiente paso natural).
- Textos hardcodeados (47 en Kotlin, 1 string en `strings.xml`).

## 4. Carátulas a resolución completa

**El problema:** cada canción usaba `content://media/external/audio/albumart/<albumId>`, que
es la miniatura que cachea MediaStore (300–500 px y a veces menos). En la carátula de 340 dp
del reproductor eso se estiraba y se veía borroso. La imagen real está **dentro del archivo**
(APIC en MP3, picture en FLAC), casi siempre entre 1000 y 3000 px.

**Local:** `EmbeddedArtwork` (nuevo, `data/artwork/`) registra un `Fetcher` de Coil para el
esquema `kukosin-cover://audio?u=<uri del audio>`. Lee `MediaMetadataRetriever.embeddedPicture`
y decodifica con `inSampleSize` según el tamaño real del composable (tope 1600 px, para no
gastar 36 MB de RAM por una portada de 3000×3000). Fallbacks, en orden:

1. imagen embebida en el archivo,
2. `contentResolver.loadThumbnail()` (API 29+, la genera el sistema desde el propio archivo),
3. `content://…/albumart/<albumId>` (lo que se usaba antes).

El `ImageLoader` se registra en `KukosinApp` (nuevo `Application`, declarado en el manifest) con
caché de memoria al 20 % y caché de disco de 64 MB para las miniaturas de red.

**La notificación y la pantalla de bloqueo** siguen usando la URI de MediaStore
(`Track.systemArtworkUri`), porque las carga media3 con su decodificador y no conoce el esquema
nuevo. Así no se rompe nada fuera de la app.

**YouTube Music:** la miniatura viene con el tamaño incrustado en la URL (`=w226-h226`). En
`YtmService.upscaleThumbnailUrl()` se reescribe a `=w1080-h1080` (y `=sNNN` → `=s1080`), que es
la imagen original que Google tiene disponible. Medido sobre las URLs del extractor: antes se
pedían 226 px para una carátula de 340 dp.

**Nota:** la carátula embebida se cachea en memoria, no en disco (Coil solo cachea en disco los
fetchers de red). Si con una biblioteca de miles de temas notás tirones al scrollear las
cuadrículas, el siguiente paso es agregar un `DiskCache` propio para esas imágenes.

## 5. Color de acento configurable — `AccentTheme.kt` (nuevo)

En **Configuración → Personalización** ahora se puede cambiar el dorado por otro tono.

- **10 presets** (`AccentPreset`): Oro, Ámbar, Cobre, Rosa, Magenta, Violeta, Azul, Cian,
  Esmeralda, Lima. Todos usan la **misma saturación (0.73) y luminosidad (0.67)** que el
  `#E8B86D` original: solo cambia el tono, así que el contraste con el fondo `Ink` y el texto
  `Cream` es idéntico en los diez. No hay ningún preset "que no se lee".
- **Slider de tono fino** (0–360°) para cualquier valor intermedio. Arrastrar solo actualiza
  el estado en memoria; al soltar (`onValueChangeFinished`) se escribe en disco una sola vez.
- **"Volver al oro"** restaura los 37° de fábrica.

Cómo funciona por dentro:

- `Color.kt`: `Gold` y `GoldDim` dejaron de ser `val` fijos y pasaron a ser **getters** que
  leen `AccentTheme.color` / `colorDim`. Los **168 usos** de `Gold` en la UI quedaron igual,
  sin tocar ninguna pantalla: se repintan solos porque leen un `mutableFloatStateOf`.
- `Theme.kt`: el `HiFiScheme` era un `val` de nivel superior (se congelaba en dorado). Ahora
  se arma dentro de `AppTheme` y se memoriza con `remember(accent, accentDim)`, así que
  `MaterialTheme.colorScheme.primary` (chips, sliders, ripples) también sigue al acento.
- `KukosinApp.onCreate()` llama a `AccentTheme.init(this)` **antes** de la primera pantalla.
- Persistencia: `SharedPreferences("kukosin_appearance")`, clave `accent_hue`, default `37f`.

Verificado compilando: **387 clases, 0 errores** (antes 377).
