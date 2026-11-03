# Análisis de Mornye y BitChord: qué sirve para el audio de waos

Dos repositorios revisados el 2026-11-01 para ver qué aportan al camino bit-perfect de
esta app:

| Repositorio | Commit | Naturaleza | Licencia |
|---|---|---|---|
| [mornyeapp/Mornye](https://github.com/mornyeapp/Mornye) | `7db0fc7` | Flutter + backend nativo (C++ y Rust); decodifica FLAC/WavPack/DSD **propios** | LGPL-3.0-only + GPL-3.0 |
| [kushagrasinghx/BitChord](https://github.com/kushagrasinghx/BitChord) | `b690dc1` | Kotlin/Compose sobre Media3 + exo; adapta el camino de Mornye a Media3 | GPL-3.0 |

> **Licencias, primero.** Waos no tiene licencia declarada. Mornye es LGPL-3.0-only y
> BitChord GPL-3.0. Por eso acá **no se copió código**: se reimplementaron las ideas y se
> adaptaron al motor propio, documentando el origen. Si en el futuro se copia código
> literal de Mornye, waos tiene que ser LGPL-3.0 (o compatible); si se copia de BitChord,
> GPL-3.0. Eso hay que decidirlo antes de distribuir un APK.

## Qué se integró (y qué problema resolvía)

| # | Idea / mecanismo | Origen | Qué se hizo en waos | Verificación |
|---|---|---|---|---|
| 1 | **Anclar el timestamp del HAL y extrapolar entre refrescos** | Mornye `native_audio.cpp` (`frames()` + `lastMusicEnd`) | `AudioEngine::updatePlayoutPosition`: se guarda (frames presentados, instante) y, cuando `getTimestamp()` no está disponible (AAudio lo publica cada varias ráfagas), se extrapola con el reloj del sistema, acotado a lo consumido. Si el timestamp **retrocede**, se re-ancla en vez de arrastrar el reloj hacia atrás. | `9b` en el arnés |
| 2 | **Rechazar el stream si el HAL no concedió lo pedido** | Mornye `ExclusiveOutput` (valida API, sharing, rate, canales, formato, device) | `AudioEngine::openStream`: si se pidió exclusivo y el HAL devolvió compartido, el stream se **cierra** y se prueba el siguiente candidato (antes se aceptaba y la UI podía mostrar "exclusivo" sin serlo). Se avisa por log si cambió formato/rate/canales. | `7a`/`7c` + revisión de logs |
| 3 | **Pocos intentos de rate alternativo** | BitChord `ExclusiveTransport` (`MAX_RATE_ATTEMPTS = 3`) | `tryOpenCandidates`: máximo 3 rates alternativos (antes 6). Cada intento abre un stream real; un DAC que niega exclusivo los niega todos. | `7d` |
| 4 | **Camino directo sin cuantizar (bit-perfect de verdad)** | BitChord `BitPerfectPcm.convert` (sólo convierte cuando hace falta) | `AudioEngine`: nuevo `directPath_`. Con señal intacta **y sin fundidos pendientes** el bloque se copia byte a byte al anillo (nunca pasa por float, así que no se re-cuantiza un 24 bits). `StreamInfo.bitPerfect` ahora es honesto: exige exclusivo + passthrough + camino directo + volumen al 100 %. | `6a` (bytes intactos) + `9a` |
| 5 | **Filtro de resampleo con ventana Kaiser larga** | BitChord `DacFormatConverter` (β 10.06, ~100 dB de rechazo) | `PolyphaseResampler`: de 24 taps / β 8.0 a 64 taps / β 10.06, **y corte dependiente de la dirección** (ver abajo). | `9a`, `9c` |
| 6 | **Volumen del DAC por USB Audio Class** | Mornye `usb_audio/volume.rs`, BitChord `UsbVolume.kt` + `DacVolume.kt` | Nuevo `DacHardwareVolume` (parser del descriptor, rangos, cuantización al paso del DAC, read-back, restauración) + `UsbDacManager.open/set/closeHardwareVolume` + UI en la hoja de ruta de señal. | `tools/dac-volume-tests` (25 comprobaciones) |
| 7 | **Caché semántica en disco** | Mornye (biblioteca que guarda metadatos ya leídos) | `LibraryCacheStore` v3 guarda **sólo lo caro** (duración, sample rate, profundidad, bitrate, canales, mime) + firma de disco; las etiquetas se toman del escaneo. `MusicLibrary.scan` aplica la caché (2 `stat` por archivo) y `enrich` sondea sólo lo que falta. | Arnés de `DiskFingerprint` + revisión |

## Un error que se encontró midiendo (y que la copia literal habría metido)

Al subir el filtro a β 10.06 y 24 taps se midió la respuesta real, y con **27 dB de banda
de rechazo en 48 → 44.1 kHz** resultó ser peor que el filtro original (corte con `0.96`
también al subir de tasa, atenuando los agudos):

| Diseño | 20 kHz (44.1→48) | 23 kHz (48→44.1, debe morir) |
|---|---|---|
| original (24 taps, β 8.0, corte plano) | −0.94 dB | −11 dB (alias audible) |
| β 10.06 con corte 0.96 en ambas direcciones | −2.7 dB | −19 dB |
| **adoptado** (64 taps, β 10.06, 0.96 sólo al bajar) | −0.08 dB | **−36 dB** (y −134 dBFS medido con 96→44.1) |

Lección: el corte del filtro depende de la dirección de la conversión. Al **subir** de
tasa manda el Nyquist de entrada (hay que ser plano hasta él); al **bajar**, el de salida
(hay que cortar antes para no plegar alias). El número de taps es lo que da la
selectividad, no el β.

## Qué NO se integró, y por qué

Traer estas piezas cambiaría la arquitectura y merece decidirse aparte:

1. **Decodificadores propios (FLAC, WavPack/DSD, DFF)** — Mornye decodifica en C++ con
   libFLAC/WavPack y alimenta su motor directamente; así su salida es bit-perfect desde el
   archivo. Waos usa Media3: el decodificador entrega float para 24 bits y el camino
   pierde el bit exacto **antes** del motor. Implementarlo implica empaquetar libFLAC y
   WavPack en el NDK, escribir el pipeline de decodificación y dejar de usar
   `MediaCodec` para FLAC/DSD. Es el siguiente paso grande, no un parche.
2. **Audio directo por USB (isócrono, sin AAudio)** — Mornye lo hace con Rust + libusb y
   BitChord con `UsbDirectTransport`. Requiere *claim*-ear la interfaz de audio (Android no
   lo permite por `UsbManager`; hay que ir por libusb/USBDEVFS) y manejar el feedback
   endpoint. El volumen de hardware que sí se integró (punto 6) es la mitad "fácil" y sirve
   igual en el camino AAudio.
3. **La app entera de Mornye, como base** — es Flutter y BitChord es Kotlin/Media3 con
   módulos de TV y Wear. Adoptar cualquiera de las dos no es integrar audio: es reescribir
   waos.

## Verificación ejecutada

```
./tools/native-tests/run.sh          -> 85 comprobaciones, 0 fallas  (nuevas: 9a, 9b, 9c)
./tools/native-tests/run.sh --asan   -> 85, 0 fallas
./tools/native-tests/run.sh --tsan   -> 85, 0 fallas
./tools/eq-tests/run.sh              -> 16, 0 fallas
./tools/dac-volume-tests/run.sh      -> 25, 0 fallas
KOTLINC=... ./tools/native-tests/kt-check.sh -> sin errores
```

**No verificado (requiere teléfono + DAC):** si un DAC concreto concede exclusivo/MMAP,
si expone Feature Unit de volumen (y con qué rango), la latencia real, y el
comportamiento de `getTimestamp()` en HALs viejos.
