# Cambios: motor de audio nativo (Oboe/AAudio) reescrito

Fecha: 2026-11-01 · Base: `168f45c`

Este documento resume **qué cambió en código**, por qué y cómo comprobarlo. La
revisión completa y los pendientes están en [`REVISION.md`](REVISION.md); la
arquitectura y los límites reales, en [`docs/ARQUITECTURA-AUDIO.md`](docs/ARQUITECTURA-AUDIO.md).

## 1. Motor nativo — `app/src/main/cpp/audio/AudioEngine.{h,cpp}`

### 1.1 Contabilidad exacta de bytes (bug corregido)

- **Antes:** el pendiente se cortaba con `outBytes_.begin() + written` …
  `outBytes_.end()`, es decir el tamaño del vector reutilizado y no la longitud del
  bloque. Con el ring lleno se encolaban bytes del bloque anterior.
- **Ahora:** no existe cola `pending_`. `writeToRing()` acepta sólo la entrada cuya
  salida garantizada cabe en el ring (con 2 frames de margen) y devuelve el resto
  como no aceptado; Media3 reenvía exactamente ese tramo
  (`bytesWritten = bytesRemaining - buffer.remaining()`).
- **Arreglo de paso:** el presupuesto por `freeFrames` existía sólo en el camino con
  resampleo; sin él, el camino de volumen < 100 % recortaba audio en silencio.

### 1.2 Fin de stream con drenado (gapless)

- `stop()` ya **no** vacía el ring ni pide `requestStop()`. Marca fin de stream,
  aplica un fade corto (sólo si los fundidos están activos) y deja que el callback
  vacíe el ring y se detenga con `DataCallbackResult::Stop`.
- Nuevo `drain(timeoutMs)`: espera a que la posición de playout alcance los frames
  encolados. Nuevo JNI `stopAndWait(handle, timeoutMs)` usado por
  `OboeAudioOutput.stop()` con 750 ms de tope.
- Las etiquetas del enum de fundido dejaron de mentir: `kFadeOutAndStop` →
  `kFadeOutAndHold` (siempre fue "fundido y pausa").

### 1.3 `flush()` en estado válido

- AAudio sólo acepta `requestFlush()` en OPEN/PAUSED/STOPPED/FLUSHED. Ahora:
  pausa → espera PAUSED → `requestFlush()` → espera FLUSHED → deja el stream en
  **PAUSED** (Media3 reutiliza el mismo `AudioOutput` tras un seek, y desde FLUSHED
  no arranca con `requestStart()`).
- Se descarta el ring propio y se resetean los contadores de posición.

### 1.4 Posición de playout

- `positionFrames()` usa `AudioStream::getTimestamp(CLOCK_MONOTONIC)`:
  `posición = framesConsumidos − latencia`, con filtro de lecturas absurdas y
  monotonía garantizada por stream.
- `positionMicros()` devuelve el tiempo en el dominio del **origen**;
  `sourceFramesQueued()` en frames de origen. Antes se mezclaban ambos dominios.
- La latencia medida se publica en `StreamInfo::latencyMicros` y en la UI.

### 1.5 Apertura de streams

- Política explícita y en este orden: exacto+exclusivo+LowLatency → exacto con el
  sharing permitido → exacto sin LowLatency → rate del equipo → formato del equipo.
- `strictBitPerfect` corta antes de los dos últimos pasos: falla en vez de mentir.
- Fallback de formato limitado a los candidatos con soporte real; se eliminó el bucle
  cartesiano de 18 intentos.
- `requireDevice`: si el `deviceId` pedido no abre, **no** se cae al dispositivo por
  defecto (modos USB).
- `setIsContentSpatialized(false)` explícito para no activar espacialización.
- `mmap` se lee de `OboeExtensions::isMMapUsed()` (incluido con `__has_include` para
  no romper si el paquete prefab no lo expone).
- Buffer de hardware: 1 ráfaga si hay MMAP, 2 en exclusivo, 3 en compartido.

### 1.6 Robustez

- `controlMutex_`: el motor se puede cerrar desde un hilo mientras otro escribe; el
  callback nunca toma el mutex.
- `close()` ya no puede quedar con un callback vivo sobre memoria liberada: el stream
  se suelta antes de limpiar los buffers.
- `stop()` es idempotente (Media3 puede llamarlo dos veces).
- En pausa el callback devuelve `DataCallbackResult::Start` cuando hay datos nuevos
  (no gasta CPU en silencio) y `Stop` cuando no los hay (sin memcpy inútil).
- Se eliminaron `targetBufferMs` sin uso y el cast a `ChannelCount` inexistente.

## 2. JNI — `app/src/main/cpp/native-lib.cpp`

Nuevas funciones y parámetros (protocolo actualizado):

| Función | Uso |
|---|---|
| `nativeStopAndWait(handle, timeoutMs)` | EOS con drenado |
| `nativeGetPositionMicros(handle)` | playout para Media3 |
| `nativeGetLatencyMicros(handle)` | latencia medida |
| `nativeIsEndOfStream(handle)` | fin de stream real |
| `nativeSetFades(handle, enabled)` | modo purista sin rampas en caliente |

`nativeConfigure` recibe un `requireDevice` adicional. `nativeGetInfo` ahora devuelve
**18** enteros (se agregaron `latencyMicros` y `mmap`).

## 3. Kotlin

- `AudioEngine.kt`: `AudioEngineConfig.requireDevice`, `EngineInfo.latencyMicros` /
  `mmap` / `latencyMs`, `streamLabel` con `· MMAP`, y los wrappers nuevos.
  `sourceFramesRendered` (frames consumidos) se reemplazó por
  `positionMicros()`/`sourceFramesQueued`.
- `OboeAudioOutput.kt`: `stop()` → `stopAndWait()`; `getPositionUs()` usa la posición
  del motor y nunca retrocede; `flush()` resetea la posición local.
- `OboeAudioOutputProvider.kt`: fail-closed si el modo necesita DAC y no hay;
  `requireDevice`; soporte declarado sólo para 1-2 canales; sin reintento al
  dispositivo 0 cuando la ruta es USB.
- `ui/SettingsScreen.kt` y `ui/Sheets.kt`: muestran camino real
  (MMAP / exclusivo / compartido) y latencia medida, en vez de asumir "MMAP" cuando
  el stream era sólo exclusivo.

## 4. Pruebas y herramientas

```
tools/native-tests/run.sh          # 70 comprobaciones del motor real en host
tools/native-tests/run.sh --asan   # + AddressSanitizer/UBSan
tools/native-tests/run.sh --tsan   # + ThreadSanitizer
tools/native-tests/kt-check.sh     # tipado de los Kotlin de audio (sin SDK)
```

- `tools/native-tests/AudioEngineTests.cpp` compila `AudioEngine.cpp` **sin
  modificarlo** (`#define private public` sólo dentro del test) contra dobles de Oboe
  que incluyen el reloj de hardware para verificar la posición de playout.
- `app/src/test/java/com/kukosin/app/audio/PcmFormatTest.kt` congela el protocolo JNI
  (códigos de formato, estado, error y etiquetas de UI).

## 5. Prueba manual sugerida

1. Reproducir una pista 44.1/16 hasta el final en cada modo: la última nota no debe
   cortarse y la siguiente pista debe entrar sin silencio (gapless).
2. Hacer un seek con la barra: no debe quedar audio de la posición anterior.
3. Pausar y reanudar: sin clic al reanudar y posición continua.
4. Con un DAC USB: desconectarlo en reproducción → la app debe reportar el error y
   reconstruir la salida, sin seguir sonando por el altavoz interno.
5. En Ajustes → motor de audio: comparar "Latency", "Path" y "Xruns" entre modos
   (MMAP debería mostrar menos latencia que "Shared").
6. Con volumen al 100 %, EQ plano y modo USB en un DAC que soporte el rate de la
   pista: "Bit-perfect" debe decir que sí.
