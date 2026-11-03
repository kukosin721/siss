# Cómo probar los cambios

Guía corta y en orden. Al final hay una tabla de "si falla X → probablemente Y".

## 0. Antes de empezar

- **Android Studio** con JDK 17 (AGP 9 lo exige) y un teléfono con **Android 7.0+** (minSdk 24).
  Para probar el DAC USB y la ruta de audio, mejor un equipo con Android 10+.
- La primera sincronización descarga **NewPipeExtractor desde JitPack** (~800 KB) +
  Rhino/protobuf/jsoup (~2,5 MB): necesita internet y tarda un poco más de lo normal.

## 1. Compilar e instalar

Por consola, desde la carpeta del proyecto:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

O simplemente el botón ▶ **Run** de Android Studio.

Si el primer sync se queja de `com.github.TeamNewPipe:NewPipeExtractor`:

```bash
./gradlew --refresh-dependencies :app:assembleDebug
```

## 2. Ver los logs (deja esto corriendo en otra terminal)

Agregué logs con dos tags para que puedas seguir qué pasa:

```bash
adb logcat -c                                   # limpiar
adb logcat -s KukosinYtm:D KukosinPlayback:D KukosinSwipe:D AndroidRuntime:E
```

En Android Studio: pestaña **Logcat** → filtro `tag:KukosinYtm | tag:KukosinPlayback`.

Lo que deberías ver, por cada canción:

```
D/KukosinYtm: search "bohemian rhapsody": 20 resultados en 1089 ms
D/KukosinPlayback: stream BSTsnWoslP4 -> Opus 160kbps (1448 ms)
D/KukosinYtm: resolveAudio(BSTsnWoslP4): Opus 160kbps itag=251 en 1448 ms
D/KukosinYtm: resolveAudio(BSTsnWoslP4): URL desde caché        ← al volver a la misma
```

Esos tiempos son los que medí de verdad desde un JVM contra el extractor real
(1,1 s la búsqueda, 1,4 s la resolución en frío).

---

## 3. Las pruebas, en orden

### A. Búsqueda en YouTube Music

1. Pestaña **YouTube Music** → escribe `bohemian rhapsody` → lupa del teclado o botón.
2. **Esperado:** spinner en el campo, después ~20 filas con carátula, título, artista,
   duración y el chip "YouTube Music". Ya **no** aparecen las canciones de ejemplo
   (Rick Astley con el `.mp4` de demo) que había antes.
3. Sin escribir nada y buscando: mensaje "Busca una canción, artista o álbum…".

### B. Reproducción y resolución del audio

1. Toca una canción.
2. **Esperado:** empieza a sonar en 1–3 s. En el log aparece `stream <id> -> Opus …`.
3. En la notificación y en el mini player debe aparecer título + artista + carátula.

### C. La cola (lo más importante de esta última tanda)

1. Con una canción sonando, toca **siguiente** ▶▶ en el mini player o el sheet.
2. **Esperado:** pasa a la siguiente de la lista de resultados y sigue sonando.
3. Abre el sheet y **desliza la carátula** a la izquierda → debe entrar la siguiente.
4. Deja que una canción termine sola → **autoplay** a la siguiente.
5. Vuelve a una canción ya escuchada → en el log debe decir `URL desde caché` y
   arrancar casi instantáneo.
6. Salta de la canción 1 a la 3 y vuelve a la 1: cada stream se resuelve una sola vez.

> Si acá ves "No se pudo resolver el audio de YouTube Music: …", el mensaje ya
> viene traducido (anti-bot, restricción de edad, sin conexión, etc.).

### D. Mini player y sheet con YouTube Music

1. Con una canción de YTM sonando, mira el **mini player** abajo.
2. **Esperado:** carátula real (thumbnail), título, artista y subtítulo
   "… · YouTube Music".
3. Tócalo para abrir el sheet: carátula grande, título, "artista — YouTube Music",
   chip **Streaming** en vez de la calidad del archivo.
4. Pestaña **Análisis**: debe explicar que el análisis espectral necesita archivos
   locales, **sin** el botón de analizar.
5. Pestaña **Letras**: "Letras no disponibles".

### E. Gesto de carátulas

1. Con una **cola local** (reproduce un álbum): abre el sheet y arrastra la carátula
   **lento**. La vecina debe asomar progresivamente desde el borde, no aparecer de golpe.
2. Suelta a mitad → vuelve al centro con rebote. Suelta pasado el 22 % o haz un flick →
   cambia de pista sin parpadeo (la carátula vieja no debe reaparecer centrada).
3. Prueba en la **primera** y **última** canción de la cola: el arrastre hacia afuera se
   siente "gomoso" y vuelve sin cambiar.
4. Repite lo mismo con una canción de **YouTube Music** (ahora la cola es toda la
   búsqueda, así que debe funcionar igual).
4b. Cada vez que soltás la carátula, en el log sale `KukosinSwipe: soltar: drag=…px
   (umbral …) vel=… nuevo=… anterior=… haySiguiente=… hayAnterior=…` y después
   `commit …: se cambio=true, indice=N`. Si `se cambio=false`, el problema es la cola,
   no el gesto; si no aparece la línea de `commit`, el gesto no llegó a decidir.
5. **Coexistencia de gestos** (esto es de la fusión): dentro del sheet, el arrastre
   **vertical** debe seguir haciendo scroll (letra, cola, ruta de señal) y el
   **horizontal sobre la carátula** cambia de tema. En la lista de la cola, el swipe
   lateral de las filas ("reproducir siguiente" / quitar) debe seguir funcionando
   igual que antes.

### E2. Calidad de carátulas

1. Abre un tema local cuyo FLAC/MP3 tenga portada de buena calidad → en el sheet la carátula
   debe verse nítida al tamaño completo, no pixelada.
2. Compara con la notificación: ahí sigue la del sistema (puede verse más chica; es esperado).
3. En YouTube Music, busca un tema y abre el reproductor: la miniatura debe llegar a 1080 px
   (`=w1080-h1080` en la URL). Si el tema no tiene arte grande en Google, se verá igual: es el
   máximo disponible en origen.

### F. Regresión: que lo local siga intacto

Esto es lo que más me importa que verifiques, porque toqué la fuente de datos del
reproductor (el `ResolvingDataSource` solo reescribe URIs `kukosin-ytm`, pero mejor
confirmarlo):

1. Reproduce un FLAC/WAV local, cambia de pista, pausa, busca en la barra de progreso.
1b. **Resampler** (lo cambié por un filtro sinc): desactiva el modo bit-perfect y fija un
   sample rate distinto al del archivo (p. ej. FLAC de 96 kHz con salida a 44.1 kHz).
   Antes eso metía un tono espurio audible; ahora no debe notarse nada raro. Si tenés un
   archivo con contenido muy agudo (platillos, ruido) es el mejor para escucharlo.
2. Abre **Análisis** en un archivo local → el análisis FFT debe seguir funcionando.
3. Conecta el DAC USB (si tienes) → el badge **DAC** y la ruta de señal deben seguir igual.

### G. Casos borde

- **Sin internet**: la búsqueda muestra "Sin conexión con YouTube…"; lo local sigue funcionando.
- **Rotación / multiventana** con YTM sonando: la reproducción no se corta.
- **Cambiar un ajuste de audio** (sample rate, bit-perfect) mientras suena YTM: el player
  se recrea y debe retomar la misma canción.

### H. Personalización: color de acento

1. Ve a **Configuración** (engranaje) → baja hasta **Personalización**.
2. Toca los círculos de color. **Todo** debe cambiar de inmediato: títulos de sección, botón
   de play, barra de progreso, badges, el chip seleccionado y el thumb del slider. No debe
   quedar ningún dorado suelto.
3. Arrastra el slider **Tono fino** y mira el círculo del preset: el check se mueve al preset
   más cercano y el número de grados se actualiza en vivo.
4. **Cierra la app por completo** (recientes → deslizar) y vuelve a abrirla: el tono debe
   seguir ahí. Esto es lo que prueba que se guardó en disco y no solo en memoria.
5. Toca **Volver al oro** → vuelve a los 37° y el preset **Oro** queda marcado.
6. **Contraste**: elige Azul y Violeta y confirma que los textos se leen igual de bien que en
   dorado (todos los presets comparten saturación/luminosidad, así que deberían).

---

## 4. Si algo falla

| Síntoma | Causa probable | Qué hacer |
|---|---|---|
| `search … falló: SignInConfirmNotBot` / "verificación anti-bot" | YouTube bloquea la IP (típico en VPN/datacenter) | Prueba con datos móviles o sin VPN; espera unos minutos |
| 0 resultados sin error | El extractor quedó desactualizado para el YTM de tu región | Sube `newpipeExtractor` en `gradle/libs.versions.toml` al último release |
| "No se pudo resolver el audio…" con mensaje de restricción | Contenido con DRM/edad/premium | Prueba otra canción para confirmar que es de esa pista |
| La app no arranca en Android 7/8 (`NoSuchMethodError`, `NoClassDefFoundError`) | Desugaring insuficiente para APIs viejas | Sube `minSdk = 26` en `app/build.gradle.kts` |
| Gradle no encuentra `NewPipeExtractor` | Caché de JitPack | `./gradlew --refresh-dependencies :app:assembleDebug` |
| Crash al abrir el sheet con YTM | Algún consumidor de `Track` que asume archivo local | Mándame el stacktrace de Logcat y lo ajusto |
| El gesto "salta" al soltar | El seek tardó más de 600 ms | Sube el `600` de `awaitIndexChange(controller, previousIndex)` en `PlayerApp.kt` |
| Suena pero sin carátula en el mini player | Thumbnail bloqueado por red/Coil | Revisa que `thumbnailUri` venga en el log de búsqueda |

## 5. Si quisieras revertir solo la cola de YTM

En `PlayerApp.kt`, el click de la lista encola **toda la búsqueda** con
`QueueManager.playItems(player, library.ytmTracks.map(YtmService::toMediaItem), startIndex)`.
El audio de cada canción se resuelve recién al abrirse el stream (no antes), así que encolar
40 resultados no hace 40 peticiones de red.

Para volver al comportamiento anterior (una sola canción) habría que pasar una lista de un
elemento y el índice 0, pero perderías siguiente/anterior y el gesto para YTM.

## 6. Probar la extracción sin el teléfono (opcional)

Si sospechas que el problema es tu red y no el código: el extractor es Java puro, así que
se puede ejecutar en el PC con un `main()` que haga `YtmService.search("…")`. Yo lo corrí
desde el sandbox y devolvió 20 resultados + `Opus 160 kbps`, así que el código está bien;
si en tu red falla, es YouTube bloqueando la IP.
