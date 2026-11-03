# Audio nativo (Oboe/AAudio) — arquitectura y límites reales

Este documento explica cómo funciona la salida de audio de waos después de la
reescritura del motor nativo, **qué se puede y qué no se puede** hacer sin el
sistema de audio de Android, y qué se verificó con pruebas automáticas.

## 1. Qué queda debajo, quieras o no

En Android **toda** aplicación de audio termina entregando sus muestras al
`AudioFlinger`, el servidor de audio del sistema. No existe una API pública para
hablarle al DAC directamente, ni para saltarse el HAL. `AudioTrack` y AAudio
(Oboe) son dos clientes distintos del **mismo** servidor:

```
 app  ──►  AudioTrack  ──►  AudioFlinger (mezclador) ──► HAL ──► DAC
 app  ──►  AAudio/Oboe ──►  AudioFlinger (MMAP o mezcla) ──► HAL ──► DAC
```

Es decir: Oboe **no** es "más potente" que Android; es una API de más bajo nivel
que permite negociar con el HAL condiciones que `MediaPlayer`/`AudioTrack`
estándar no dejan pedir. Lo que sí cambia, y es mucho:

| Capacidad | AudioTrack (mezclador) | AAudio exclusivo / MMAP |
|---|---|---|
| Frecuencia = la de la pista | el sistema resamplea | sí, sin conversión |
| Profundidad 24/32 bits | limitada | sí |
| Fast mixer / efecto de volumen | sí (atenúa y aplica ganancia) | no en exclusivo |
| Latencia de buffer | la del mezclador | 1 ráfaga (MMAP) |
| Timestamp de playout real | vía `AudioTrack` | vía `AAudioStream_getTimestamp` |
| Garantía bit-perfect | no | sí, mientras el HAL lo conceda |

Y lo que **nunca** se puede esquivar:

1. **El HAL puede rechazar** el formato o el rate. Si el DAC solo acepta 48 kHz
   (muy común en DACs USB baratos y en el jack de muchos equipos), ahí la app
   decide: reventar el modo estricto o resamplear en casa. Ambas opciones están
   implementadas y son explícitas.
2. **El volumen del sistema puede seguir aplicándose.** En exclusivo/MMAP Android
   no deja aplicar ganancia digital en el mezclador, pero el DAC y el
   amplificador interno tienen su propia etapa. Por eso existe el modo USB con
   volumen fijo: para que la señal llegue sin atenuación *de la app*.
3. **El sistema puede pausar/robar el stream** (llamada, notificación,
   desconexión del DAC). El motor lo detecta y lo reporta como error para que
   Media3 reconstruya el sink.
4. **No hay acceso a relojes del DAC** (ni S/PDIF, ni MQA, ni DSD vía AAudio).

## 2. Qué hace el motor nuevo

`app/src/main/cpp/audio/AudioEngine.{h,cpp}` implementa un motor de salida con
una sola responsabilidad: **mover PCM ya decodificado hacia el DAC con la menor
cantidad de transformaciones posible y con contabilidad exacta**.

### 2.1 Política de apertura (de más fiel a más compatible)

| Paso | Formato | Sharing | Cuándo |
|---|---|---|---|
| 1 | el de la pista | `Exclusive` | siempre se intenta primero |
| 2 | el de la pista | `Shared` | si el modo no exige exclusivo |
| 3 | el de la pista | `Exclusive` sin MMAP forzado | reintento conservador |
| 4 | rate nativo del equipo + formato de la pista | según modo | si el HAL rechaza el rate |
| 5 | rate/formato del equipo + conversión propia | `Shared` | solo si `strictBitPerfect == false` |

`requireExclusive` corta en el paso 1. `strictBitPerfect` corta en el 4: si el
equipo no acepta el formato exacto, el motor **falla** en vez de mentir. Esto
antes no era así: el fallback por conversión se intentaba *y* además se aplicaba
al mismo tiempo, lo que producía resampleo doble en algunos equipos.

Todas las aperturas se hacen con `setFormatConversionAllowed(false)`,
`setChannelConversionAllowed(false)` y `SampleRateConversionQuality::None`: si el
HAL no puede dar exactamente lo pedido, **falla** y el motor decide; nunca
convierte dos veces.

### 2.2 Canal: una sola vía, sin colas obsoletas

```
Media3 ──write()──► [ convertidor: decodifica → volumen → canales → resample → codifica ]
                                   │  (solo acepta lo que ya tiene lugar)
                                   ▼
                         ring SPSC (memoria propia, del tamaño del buffer)
                                   │
                     callback de audio: memcpy + fundido, sin asignaciones
                                   ▼
                                  DAC
```

Reglas que hacen que no se pierda ni se duplique audio:

* El ring **nunca** guarda frames parciales: se escribe y se lee alineado al
  frame del stream.
* El convertidor solo procesa los frames cuyo resultado **ya cabe** en el ring.
  Lo que no cabe se devuelve como "no aceptado" (`write()` devuelve el número de
  bytes de origen aceptados) y Media3 reintenta el mismo buffer. Se eliminó la
  cola `pending_` que antes podía arrastrar bytes del bloque anterior.
* El callback nunca reserva memoria, nunca toma locks y nunca registra logs.

### 2.3 Fin de stream y gapless

`AudioOutput.stop()` de Media3 significa "deja sonar lo que queda y para"
(`DefaultAudioSink.playToEndOfStream()` → `stop()`). El motor lo implementa:

1. `stop()` marca fin de stream y **no** vacía nada.
2. El callback sigue vaciando el ring y devuelve `DataCallbackResult::Stop`
   solo cuando no queda audio real.
3. `drain()` espera, con timeout, a que la **posición de playout** alcance los
   frames escritos.
4. Recién ahí Media3 puede declarar el stream terminado y encadenar la pista
   siguiente sin cortar muestras.

### 2.4 Reloj de posición

`getPositionUs()` no puede ser "lo que el callback consumió" (eso adelanta la
posición en buffer + hardware). El motor calcula la posición de playout:

* `AAudioStream_getTimestamp()` (`AudioStream::getTimestamp`) cuando el stream
  está `Started`; se mide la latencia real `framesConsumidos − posiciónHW` y se
  guarda como última latencia conocida.
* Cuando el stream se pausa o termina, la posición se estima con esa última
  latencia en vez de saltar hacia atrás.
* La posición nunca retrocede dentro de un mismo stream.

## 2.5 Antes del motor: de dónde salen las muestras (FLAC)

El motor escribe al DAC lo que le llegue. Hasta la Ronda 4, un FLAC llegaba así:

```
.flac → FlacExtractor (Media3) → MediaCodec (c2.android.flac.decoder) → ¿float o 16 bits?
      → ToFloatPcmAudioProcessor → AudioSink → OboeAudioOutput → motor
```

`c2.android.flac.decoder` no tiene camino de 24 bits enteros: emite 16 bits o float. El
camino float multiplica por `1/0x7FFFFFFF`, y un `int24` en un float de 32 bits (24 de
mantisa) no vuelve intacto. El motor podía ser perfecto y la muestra ya venía tocada.

Desde la Ronda 4:

```
.flac (local) → LocalFlacMediaSourceFactory → FlacPcmExtractor → FlacDecoderBridge (JNI)
             → FlacDecoder (C++, RFC 9639) → audio/raw 16/24/32 bits → AudioSink → motor
```

El decodificador propio (sin libFLAC) entrega PCM entero en el ancho nativo del archivo y
`AudioEngine` lo escribe sin conversión. Verificado bit a bit contra libFLAC en el host
(428 comprobaciones); detalle en [`FLAC-PROPIO-Y-LGPL.md`](FLAC-PROPIO-Y-LGPL.md).

## 3. Qué se verificó (y qué no)

`tools/native-tests/` compila el **motor real** en un host Linux con dobles de
Oboe y `log.h`, y ejecuta pruebas de contrato: integridad del ring concurrente,
contabilidad de bytes, EOS sin pérdida, posición monótona, flush en estado
inválido, fidelidad bit a bit en el camino sin conversión, conversión de
canales y formato.

Lo que **no** se puede verificar sin un dispositivo: latencia real, xruns,
comportamiento del HAL con cada DAC, y si el equipo concede MMAP/exclusivo. Eso
está en `PROBAR.md` como prueba manual.
