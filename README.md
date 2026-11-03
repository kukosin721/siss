# waos

Reproductor de audio local + YouTube Music para Android, con motor de audio propio
(Oboe/AAudio) y un camino lo más directo posible hacia el DAC.

## Qué lo distingue

- **Motor nativo propio** (`app/src/main/cpp/audio/AudioEngine.cpp`): anillo SPSC
  lock-free, contabilidad exacta de bytes, fin de stream con drenado (sin cortar el
  último buffer), `flush()` sólo en estados válidos de AAudio y posición de playout
  calculada con el *timestamp* del HAL (no con "frames escritos"), que es lo que hace
  que el reloj no se adelante al sonido.
- **Camino de salida honesto**: intenta formato exacto + exclusivo + MMAP; si el equipo
  no lo concede, lo dice (MMAP / exclusivo / compartido) en lugar de simular bit-perfect.
  La UI muestra el camino real y la latencia medida.
- **Ecualizador real**: 10 bandas + notch/paso bajo/paso alto (RBJ), suavizado de
  coeficientes en el tiempo (sin clics al mover un control), preamp, headroom automático
  y medición de la curva que dibuja contra la que suena.
- **Volumen sin tocar las muestras**: el atenuador del propio DAC por USB Audio Class
  (Feature Unit), con read-back y restauración al salir; si el DAC no expone el control,
  se dice en vez de fingir que el volumen funciona.
- **Búsqueda por letra** con índice normalizado (sin acentos, sin mayúsculas) para que
  buscar no dependa de abrir miles de archivos en cada tecla.

## Límites reales (importante)

Sin root **no** se puede evitar AudioFlinger/AAudio ni el remuestreo del HAL cuando el
equipo lo impone. Lo máximo alcanzable es: modo exclusivo/MMAP cuando el HAL lo concede,
formato nativo, DSP y reloj propios, y salida directa por USB. Cualquier afirmación de
"bit-perfect garantizado" en todos los equipos sería falsa. Detalle en
[`docs/ARQUITECTURA-AUDIO.md`](docs/ARQUITECTURA-AUDIO.md) y
[`CAMBIOS-AUDIO-OBOE.md`](CAMBIOS-AUDIO-OBOE.md).

## Compilar

```bash
./gradlew assembleDebug     # APK de depuración
./gradlew test              # pruebas JVM (protocolo JNI/PCM, índice de letras)
```

Requiere JDK 17, Android SDK con NDK (el motor nativo se compila con CMake + Oboe).

## Verificación sin dispositivo

Todo lo que se puede verificar sin un teléfono está automatizado:

```bash
./tools/native-tests/run.sh          # motor nativo en host, con dobles de Oboe
./tools/native-tests/run.sh --asan   # AddressSanitizer + UBSan
./tools/native-tests/run.sh --tsan   # ThreadSanitizer
./tools/eq-tests/run.sh              # DSP del ecualizador (respuesta real medida)
./tools/dac-volume-tests/run.sh      # volumen del DAC (descriptores USB sintéticos)
./tools/flac-tests/run.sh            # decodificador FLAC propio: byte a byte vs. libFLAC
./tools/flac-tests/run.sh --asan     # idem, con AddressSanitizer + UBSan
KOTLINC=… ./tools/native-tests/kt-check.sh   # tipos Kotlin sin SDK
```

Lo que **no** cubren estas pruebas: latencia real por DAC, xruns, si un equipo concreto
concede MMAP, el comportamiento de `getTimestamp()` en HALs antiguos, y el extractor FLAC
dentro de Media3 en un teléfono (del lado nativo sí está verificado bit a bit).

## Documentación

- [`REVISION.md`](REVISION.md) — revisión técnica: hallazgos con evidencia, cambios y pendientes.
- [`docs/ARQUITECTURA-AUDIO.md`](docs/ARQUITECTURA-AUDIO.md) — cómo encaja Media3 + Oboe + EQ.
- [`CAMBIOS-AUDIO-OBOE.md`](CAMBIOS-AUDIO-OBOE.md) — detalle del rework del motor.
- [`PROBAR.md`](PROBAR.md) — qué probar en el dispositivo, paso a paso.
- [`docs/ANALISIS-MORNYE-BITCHORD.md`](docs/ANALISIS-MORNYE-BITCHORD.md) — qué se tomó de
  esos dos reproductores bit-perfect, qué no, y por licencias.
- [`docs/FLAC-PROPIO-Y-LGPL.md`](docs/FLAC-PROPIO-Y-LGPL.md) — decodificador FLAC propio:
  cómo funciona la LGPL, por qué se implementó desde cero y cómo se verificó.
- [`docs/FLUIDEZ-Y-SIN-CORTES.md`](docs/FLUIDEZ-Y-SIN-CORTES.md) — por qué había corte al
  cambiar de pista, qué presets hay, qué se midió y qué animaciones se cambiaron.

## Licencia

Todavía sin definir por el autor del proyecto. Las dependencias conservan sus licencias
(NewPipeExtractor es GPL-3.0, Media3/Oboe son Apache-2.0): antes de publicar el APK hay
que elegir una licencia compatible y declararla acá.
