# Revisión técnica y reescritura del motor de audio

- **Repositorio:** https://github.com/kukosin721/waos
- **Commit revisado:** `168f45ceb9490bf32209b1eec54931103230ce0d` (`master`)
- **Fecha:** 2026-11-01
- **Alcance:** lectura completa del Kotlin/Compose, el motor C++/Oboe, la integración
  con Media3 1.11.1, la capa de datos y las herramientas; más compilación del motor
  nativo real en host con dobles de Oboe para verificar comportamiento.
- **Estado de verificación:** el motor nativo y el tipado de los archivos tocados se
  verificaron en un host Linux. **No** se ejecutó Gradle, ni tests instrumentados, ni
  pruebas en dispositivo.

---

## 1. Qué se cambió en esta revisión (resumen)

| # | Cambio | Archivos |
|---|---|---|
| 1 | Motor nativo reescrito: contabilidad exacta, EOS con drenado, flush en estado válido, posición de playout con timestamp, MMAP y política de apertura explícita. | `app/src/main/cpp/audio/AudioEngine.{h,cpp}` |
| 2 | JNI ampliado: `stopAndWait`, `positionMicros`, `latencyMicros`, `isEndOfStream`, `requireDevice`, `setFades`, info de latencia y MMAP. | `app/src/main/cpp/native-lib.cpp` |
| 3 | Adaptador Media3: `stop()` drena, `getPositionUs()` es playout, `flush()` deja el stream reutilizable. | `app/src/main/java/com/kukosin/app/audio/OboeAudioOutput.kt` |
| 4 | Proveedor: fail-closed en rutas USB, canales honestos (≤2), `requireDevice`. | `app/src/main/java/com/kukosin/app/audio/OboeAudioOutputProvider.kt` |
| 5 | Puente Kotlin: nuevos campos (`latencyMicros`, `mmap`), `requireDevice`, wrappers nuevos. | `app/src/main/java/com/kukosin/app/audio/AudioEngine.kt` |
| 6 | UI: muestra camino real (MMAP / exclusivo / compartido) y latencia medida. | `ui/SettingsScreen.kt`, `ui/Sheets.kt` |
| 7 | Pruebas del motor en host (70 comprobaciones, ASan/UBSan y TSan limpias) + test JVM del protocolo. | `tools/native-tests/`, `app/src/test/java/com/kukosin/app/audio/PcmFormatTest.kt` |
| 8 | Documentación de arquitectura y límites reales. | `docs/ARQUITECTURA-AUDIO.md`, `CAMBIOS-AUDIO-OBOE.md` |

### Ronda 2 (misma fecha): fluidez, EQ profesional y bloqueo de ajustes

| # | Cambio | Archivos |
|---|---|---|
| 9 | EQ profesional: notch/paso bajo/paso alto RBJ, suavizado en espacio de coeficientes, guardas anti-NaN, headroom automático. | `eq/Biquad.kt`, `eq/EqModel.kt`, `eq/EqEngine.kt` |
| 10 | Bloqueo al cambiar ajustes: la reconstrucción del player ya no ocurre en el hilo principal; debounce de ajustes, guardas de reentrada y limpieza de listeners antes de `release()`. | `playback/PlaybackService.kt` |
| 11 | Apertura del stream de audio en su propio hilo nativo (no bloquea el sink ni el hilo de audio). | `cpp/audio/AudioEngine.{h,cpp}`, `cpp/native-lib.cpp`, `audio/AudioEngine.kt` |
| 12 | Seek fluido: el reloj sigue al dedo durante el arrastre y se manda **un solo** `seekTo` al soltar. | `ui/PlayerViews.kt`, `ui/component/KukosinWaveformSlider.kt` |
| 13 | Al elegir una canción se abre el reproductor (biblioteca, YouTube Music y hojas de álbum/artista/género, que además se cierran). | `ui/PlayerApp.kt` |
| 14 | Búsqueda: índice de letras normalizado en disco, caché de resultados, debounce y arreglo del desplazamiento del campo. | `data/analysis/LyricsIndex.kt`, `data/analysis/EmbeddedMetadata.kt`, `ui/LibraryViewModel.kt`, `ui/LibraryViews.kt`, `ui/PowerampSections.kt` |
| 15 | Ondas: caché en disco (se decodifica una sola vez por canción). | `data/analysis/WaveformExtractor.kt` |

### Ronda 3: integración de ideas de Mornye y BitChord

Análisis cruzado en [`docs/ANALISIS-MORNYE-BITCHORD.md`](docs/ANALISIS-MORNYE-BITCHORD.md).

| # | Cambio | Archivos |
|---|---|---|
| 16 | Reloj: ancla del timestamp del HAL + extrapolación entre refrescos, sin retrocesos. | `cpp/audio/AudioEngine.{h,cpp}` |
| 17 | El stream se rechaza si el HAL no concedió el exclusivo pedido (nunca "exclusivo" falso); reintentos de rate acotados a 3. | `cpp/audio/AudioEngine.cpp` |
| 18 | Camino directo sin float (no re-cuantiza 24 bits) y `bitPerfect` honesto en el motor. | `cpp/audio/AudioEngine.{h,cpp}` |
| 19 | Resampler: 64 taps / β 10.06 y corte según la dirección de la conversión (medido: 48→44.1 pasa de −11 dB a −36 dB de rechazo de alias). | `cpp/audio/AudioEngine.{h,cpp}`, `tools/native-tests` |
| 20 | Volumen del DAC por USB Audio Class (Feature Unit): subir/bajar sin tocar muestras, con read-back y restauración. | `playback/DacHardwareVolume.kt`, `playback/UsbDacManager.kt`, `audio/OboeAudioOutput.kt`, `ui/Sheets.kt` |
| 21 | Caché de biblioteca semántica: sólo lo caro se guarda (v3), etiquetas siempre frescas, `scan` aplica la caché y `enrich` sondea sólo lo que falta. | `data/LibraryCacheStore.kt`, `data/MusicLibrary.kt` |

### Ronda 4: decodificador FLAC propio (sin libFLAC)

Detalle y justificación de licencia en [`docs/FLAC-PROPIO-Y-LGPL.md`](docs/FLAC-PROPIO-Y-LGPL.md).

| # | Cambio | Archivos |
|---|---|---|
| 22 | Decodificador FLAC propio en C++ (RFC 9639), sin dependencias: metadatos, CONSTANT/VERBATIM/FIXED/LPC, Rice 0/1 con escape, estéreo LR/LS/RS/MS, **CRC-8 y CRC-16 verificados**, salida PCM entera en el ancho nativo (16/24/32). | `cpp/audio/FlacDecoder.{h,cpp}` |
| 23 | Índice de frames armado recorriendo **sólo cabeceras** (validando CRC-8): permite buscar en archivos sin SEEKTABLE. Reapertura en medio del archivo (`openFrom`). | `cpp/audio/FlacDecoder.cpp` |
| 24 | Puente JNI: abrir por path o por `FileDescriptor`, leer bloques, SEEKTABLE, STREAMINFO crudo, índice de frames. `pread` para que decodificador e indexador no compartan el offset del archivo. | `cpp/flac-jni.cpp`, `playback/FlacDecoderBridge.kt` |
| 25 | Extractor de Media3 que **decodifica**: entrega `audio/raw` con `pcmEncoding` 16/24/32 bits, un bloque de FLAC = un sample, SeekMap de SEEKTABLE o del índice, posición del `DataSource` sincronizada. | `playback/FlacPcmExtractor.kt` |
| 26 | Enrutado por pista: FLAC local → decodificador propio; todo lo demás → pipeline habitual. El ajuste `decoder` sigue mandando (elegir MediaCodec vuelve al camino de Media3). | `playback/LocalFlacMediaSourceFactory.kt`, `playback/PlaybackService.kt` |
| 27 | Arnés de 428 comprobaciones: decodificación completa **byte a byte contra libFLAC** (libsndfile como oráculo), seek, reapertura en cada frame del índice, cobertura del índice, corrupción detectada y fin de stream exacto. | `tools/flac-tests/` |
| 28 | CI: los tres modos del arnés FLAC (normal, ASan+UBSan, TSan) y la dependencia de Python en el job nativo. | `.github/workflows/ci.yml` |

### Ronda 5: fluidez, sin cortes al cambiar de pista

Detalle en [`docs/FLUIDEZ-Y-SIN-CORTES.md`](docs/FLUIDEZ-Y-SIN-CORTES.md).

| # | Cambio | Archivos |
|---|---|---|
| 29 | `flushForTransition()`: vaciado del motor **sin esperar** las transiciones de estado del HAL (en `flush()` eran hasta ~240 ms que, entre canción y canción, se escuchaban como bache). | `cpp/audio/AudioEngine.{h,cpp}`, `cpp/native-lib.cpp`, `audio/AudioEngine.kt`, `audio/OboeAudioOutput.kt` |
| 30 | Preroll del arranque configurable (`prerollMs`): `0` arranca con una ráfaga en vez de 2 o 20 ms. | `cpp/audio/AudioEngine.{h,cpp}`, `audio/AudioEngine.kt`, `audio/OboeAudioOutputProvider.kt` |
| 31 | Presets de continuidad (Stable / Balanced / Instant) que mueven buffer + preroll + fundidos juntos, expuestos en Ajustes. | `playback/ContinuityPreset.kt`, `playback/AudioSettingsManager.kt`, `ui/SettingsScreen.kt` |
| 32 | Curva de volumen perceptual (v^2,5) configurable; al 100 % la ganancia es exactamente 1 (bit-perfect intacto). | `cpp/audio/AudioEngine.{h,cpp}`, `playback/AudioSettingsManager.kt` |
| 33 | Precarga de 12 s de la pista siguiente (`setPreloadConfiguration`): el `next` no paga el arranque del decoder. | `playback/PlaybackService.kt` |
| 34 | Caché de arranque de la biblioteca: la lista completa se dibuja al instante y el escaneo real la reemplaza detrás; `scanJob` único; precarga de carátulas de favoritos y más escuchadas en la caché de memoria de Coil. | `data/StartupCache.kt`, `data/LibraryInsights.kt`, `KukosinApp.kt`, `ui/LibraryViewModel.kt` |
| 35 | Un vocabulario de movimiento (`Motion.kt`): tres curvas con criterio, todas escaladas por el ajuste de velocidad; se eliminaron los `tween`/`spring` sueltos de 9 archivos. | `ui/Motion.kt` + `ui/*.kt` |
| 36 | Blur de transiciones configurable (Off / Soft / Strong) publicado por `LocalUiSettings`; con Off no se ejecuta ninguna pasada de `RenderEffect`. | `ui/UiSettings.kt`, `ui/theme/Theme.kt`, `ui/PlayerViews.kt`, `ui/SettingsScreen.kt` |
| 37 | Carátula con fundido cruzado entre pistas (sin hueco al cambiar de canción) y barra de progreso que sigue al reloj en 180 ms en vez de 420 lineales. | `ui/PlayerViews.kt`, `ui/PlaybackSeekBar.kt` |
| 38 | Arnés del motor: 121 comprobaciones (antes 85) con 3 casos nuevos: flush de transición no bloqueante, preroll y curva de volumen. | `tools/native-tests/AudioEngineTests.cpp` |

---

## 2. Hallazgos de la revisión (con evidencia)

### P1 · Bug confirmado: cola de bytes obsoletos
`AudioEngine.cpp:757-760` (versión original) cortaba el pendiente desde
`outBytes_.begin() + written` hasta **`outBytes_.end()`** (tamaño del vector
reutilizado) en vez de hasta la longitud lógica del bloque.

Reproducido compilando el motor original contra dobles:

```
Ring lleno: 64 bytes
Llamada A: pending = 48 bytes (correcto)
Llamada B (16 bytes ofrecidos): pending = 48 bytes, 32 de ellos viejos (0x11)
```

→ Audio rancio/duplicado y longitudes falsas en `write()`. **Corregido** eliminando
la cola `pending_`: el motor sólo acepta la entrada cuya salida garantizada ya cabe
en el ring, y devuelve el resto como "no aceptado" (Media3 reenvía ese tramo).

### P1 · Fin de stream y gapless truncados
`DefaultAudioSink.playToEndOfStream()` (1.11.1, líneas 1353-1359) llama
`playPendingData()` → `audioOutput.stop()` (1926-1934), y `isEnded()`/`hasPendingData()`
comparan los frames escritos contra `getPositionUs()`. El `stop()` original sólo
pedía fade-out + `requestStop()`: el anillo nunca se vaciaba y el HAL descartaba lo
que no había sonado. **Corregido**: `stop()` marca fin de stream, el callback vacía
el ring y se detiene solo; `stopAndWait()` espera el drenado con timeout (750 ms).

### P1 · Fallback USB que contradice la política de seguridad
`OboeAudioOutputProvider.kt:71-76,108,121` avisaba "no hay DAC" y seguía abriendo el
dispositivo 0, con volumen fijo al 100 % en modo USB (`OboeAudioOutput.kt:98`): audio
a escala completa por el altavoz interno. **Corregido**: fail-closed
(`requireUsb`/`targetsUsb` sin DAC ⇒ `InitializationException`) y `requireDevice` en
el motor para que un id viejo no caiga al dispositivo por defecto.

### P1 · Reloj de posición
`getPositionUs()` devolvía frames consumidos por el callback, no playout: adelanta la
posición en buffer + hardware y hace que el sink declare el fin antes de tiempo.
**Corregido**: `AAudioStream_getTimestamp` (vía `AudioStream::getTimestamp`), latencia
medida, clamp y monotonía por stream.

### P2 · `flush()` en estado inválido
La documentación de AAudio es explícita: *"This call only works if the stream is OPEN,
PAUSED, STOPPED, or FLUSHED. Calling this function when in other states … will have no
effect and an error will be returned."* El código pedía flush desde `Started` y sólo
registraba un warning. **Corregido**: pausa → espera PAUSED → flush → espera FLUSHED →
queda en PAUSED para que Media3 reutilice el `AudioOutput` tras un seek.

### P2 · Canales > 2 declarados como estéreo
Se prometía `FORMAT_SUPPORTED_DIRECTLY` para cualquier PCM y luego se forzaba máscara
estéreo. **Corregido**: soporte declarado sólo para 1-2 canales; por encima, Media3
hace el downmix.

### P2 · Caché de metadatos obsoleto *(no tocado en esta revisión)*
`MusicLibrary.probeCached` (90-95) devuelve la entrada por URI sin validar
tamaño/mtime/versión; sólo `invalidateProbeCache` manual la limpia. Recomendación:
incluir `size`/`mtime`/`versión` en la clave. Igual de pendiente:
`LibraryCacheStore` (delete-before-rename e ignorar el resultado del rename).

### P3 · Menores
- `ytm`: TTL fijo de 90 min para URLs de stream, sin deduplicación de fallos
  concurrentes de caché, y el downloader traga errores de lectura del body.
- Repo: sin `README`, `LICENSE`, `CONTRIBUTING` ni CI; `.idea` versionado; ~6 MB de
  Google Sans sin archivo de licencia; `ExampleUnitTest` de plantilla.
- Mantenibilidad UI: `QueueSheet` 1.486, `PowerampSections` 1.421, `PlayerViews`
  1.142, `PlayerApp` 1.130 líneas.
- Toolchain: el wrapper pide JDK 25 y la documentación dice 17; conviene fijarlo en
  `README`/toolchain para que un clon nuevo compile a la primera.

### No es un bug
El rango de recuperación de errores de audio `5000..5099` es correcto: cubre
`ERROR_CODE_AUDIO_TRACK_INIT_FAILED` (5001), `AUDIO_TRACK_WRITE_FAILED` (5002),
`AUDIO_TRACK_OFFLOAD_WRITE_FAILED` (5003) y `OFFLOAD_INIT_FAILED` (5004) de
`PlaybackException` 1.11.1.

---

## 3. Límites reales (importante)

Reescribir el motor **no elimina el sistema de audio de Android**: no existe API
pública para hablarle al DAC directamente. Oboe/AAudio es un cliente de bajo nivel del
mismo `AudioFlinger`; lo que permite es negociar exclusividad, rate y profundidad, y
usar el camino MMAP (sin mezclador). Los detalles y la tabla comparativa están en
`docs/ARQUITECTURA-AUDIO.md`.

Consecuencias prácticas de esta reescritura:

- **Fidelidad:** camino bit-perfect real cuando el HAL concede formato + rate +
  exclusivo; sin doble conversión (antes podía resamplear el HAL **y** el motor).
- **Latencia:** buffer de hardware de 1 ráfaga cuando hay MMAP (antes 2-3 fijas) y
  medida real expuesta en la UI.
- **Estabilidad:** sin colas parciales, sin bytes rancios, EOS que no corta muestras y
  flush que deja el stream reutilizable.
- **Lo que sigue dependiendo del sistema:** el HAL puede rechazar el formato (entonces
  o falla el modo estricto o se resamplea en casa), el volumen del DAC/amplificador no
  se controla desde la app, y el sistema puede robar el stream (llamadas, desconexión
  del DAC) — el motor lo reporta como error para que Media3 reconstruya el sink.

---

## 4. Verificación ejecutada

| Qué | Cómo | Resultado |
|---|---|---|
| Motor nativo (121 comprobaciones) | `tools/native-tests/run.sh` | 0 fallas |
| Memoria / UB | `run.sh --asan` | limpio |
| Carreras de datos | `run.sh --tsan` | limpio |
| Tipado de los Kotlin tocados (audio + EQ + índice de letras) | `KOTLINC=… tools/native-tests/kt-check.sh` | sin errores |
| Ecualizador (16 mediciones de DSP) | `./tools/eq-tests/run.sh` | 0 fallas |
| Normalización de letras (8 casos + 4 invariantes) | arnés del índice | 0 fallas |
| Firma de disco de la caché (6 casos) | arnés `DiskFingerprint` | 0 fallas |
| Volumen de hardware del DAC (25 casos, UAC1/UAC2) | `./tools/dac-volume-tests/run.sh` | 0 fallas |
| Protocolo JNI (códigos/etiquetas) | `PcmFormatTest.kt` | listo para `./gradlew test` |
| Decodificador FLAC (428 comprobaciones, bit a bit vs. libFLAC) | `./tools/flac-tests/run.sh` | 0 fallas |
| Decodificador FLAC · memoria/UB | `run.sh --asan` | limpio (encontró la lectura fuera de rango en mono) |
| Decodificador FLAC · carreras | `run.sh --tsan` | limpio |

Cobertura de las pruebas nativas: ring SPSC concurrente (1 MB), contabilidad con ring
lleno, EOS con drenado completo, `stop()` sin datos, reanudación gapless, flush desde
STARTED, posición/latencia con timestamps, monotonía tras pausa, passthrough bit a bit
(24 bits), mono→estéreo, resampleo 44.1→48 kHz, modo estricto vs. fallback,
`requireDevice` y desconexión del DAC.

Cobertura del arnés FLAC: 16/24 bits × 44.1/48/96 kHz × mono/estéreo, con rampa, tonos +
ruido, silencio digital y ruido a fondo de escala (ejercita `FIXED`, `LPC`, `CONSTANT`,
`VERBATIM` y Rice con parámetro alto). Por archivo: metadatos, PCM completo byte a byte,
seek contra la lectura secuencial, reapertura en cada entrada del índice de frames, cobertura
del índice y detección de un byte dañado.

**No verificado (requiere dispositivo):** cuántos milisegundos gana cada preset de
continuidad en un DAC real (el motor ya no agrega esperas propias, pero el buffer del HAL y
el tiempo de apertura del driver son del equipo); latencia real por DAC, xruns, si cada equipo
concede MMAP/exclusivo, el comportamiento de `getTimestamp()` en HALs viejos, y —importante
en esta ronda— el extractor FLAC de Media3 en un teléfono: acá sólo se verificó su tipado
contra stubs con las firmas de Media3 1.5.1, más el decodificador que usa por dentro. El
`FlacDecoderBridge` tampoco se ejecutó bajo Android.

---

## 5. Pendientes P2 · estado

| # | Pendiente | Estado | Dónde |
|---|---|---|---|
| 1 | `probeCached` validado por archivo (tamaño + mtime) | **hecho** | `data/MusicLibrary.kt`, `data/LibraryCacheStore.kt` (`Fingerprint`) |
| 2 | Escritura atómica de la caché (tmp → `fsync` → rename, con comprobación) | **hecho** | `data/LibraryCacheStore.kt` (`VERSION` 2) |
| 3 | `ytm`: TTL de búsquedas + deduplicación de peticiones concurrentes | **hecho** | `data/ytm/YtmService.kt` (`SEARCH_CACHE_TTL_MS`, `putIfAbsent`) |
| 4 | CI mínima (Gradle + motor nativo + EQ + tipado) | **hecho** | `.github/workflows/ci.yml` |
| 5 | `README`, `LICENSE` y licencia de fuentes | **pendiente**: requiere decisión del autor (qué licencia y si se redistribuyen las fuentes incluidas) | — |

Sobre el punto 3 original ("propagación de errores de lectura del body"): el error de
red en `search`/`resolveAudio` ya se convierte en `YtmException` con mensaje
(`describeYtmError`); lo que faltaba era no repetir la misma petición N veces, que es lo
que se corrigió.

Nota sobre `README`/`LICENSE`: no se crean con una licencia inventada a propósito. Si el
autor confirma (por ejemplo MIT o Apache-2.0), se agregan en una línea; las fuentes de
NewPipeExtractor que use el proyecto deben conservar su GPL-3.0 y eso hay que declararlo.
