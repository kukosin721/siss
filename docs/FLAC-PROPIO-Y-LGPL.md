# Decodificador FLAC propio (sin libFLAC) y por qué no hay problema de licencia

Documento de la Ronda 4. Cubre tres cosas: **(1)** cómo funciona la LGPL y qué implica para
este proyecto, **(2)** qué se agregó (un decodificador FLAC propio en C++ con verificación
bit a bit contra libFLAC) y **(3)** cómo entra en la cadena de reproducción para que un FLAC
de 24 bits llegue al DAC sin pasar por float.

---

## 1. LGPL: la parte que importa (y la premisa que hay que corregir)

### 1.1Qué permite la LGPL-3.0

La LGPL es la GPL con una excepción: podés **enlazar** una biblioteca LGPL dentro de una app
propietaria sin abrir el código de tu app. Las condiciones son:

| Obligación | Qué significa en la práctica |
| --- | --- |
| Avisos | Mantener el copyright y la licencia de la biblioteca LGPL. |
| Código de la biblioteca | Entregar el código de **esa biblioteca** (o una oferta escrita válida). |
| Reemplazabilidad | Que el usuario pueda reemplazar o modificar la biblioteca (por ejemplo, reenlazando). En Android esto es lo que hace divertido el asunto: si la biblioteca es parte de un `.so` único, hay que darle al usuario la forma de reenlazarlo. |
| Modificaciones | Si modificás **la biblioteca** (no tu app), esos cambios siguen siendo LGPL. |

GPL-3.0 (sin la "L") es otra cosa: es contagiosa. Enlazar código GPL obliga a publicar toda
la obra bajo GPL. Varios archivos de BitChord derivan de Mornye y están marcados
`SPDX-LGPL-3.0-only`; el repositorio se documenta como GPL-3.0. Para este proyecto eso
significa: **no se puede enlazar nada de ahí** sin relicenciar la app completa.

### 1.2 La premisa a corregir: "es un proyecto propio, no lo voy a distribuir"

Un repositorio **público en GitHub es distribución**. No es una zona gris:

- Los términos de servicio de GitHub (§4) dicen que al hacer público un repositorio das
  permiso a otros a verlo, hacer fork y clonarlo. Eso es, literalmente, poner copias en
  manos de terceros.
- El hecho de que vos no generes un APK no cambia nada: el código fuente es la obra.
- Si nunca se hace `git push` (o el repositorio es privado) la situación cambia, pero
  conviene no diseñar la licencia asumiendo un "no voy a publicar" que puede cambiar.

**Conclusión**: aunque el código lo escriba yo de cero, una dependencia LGPL metería
obligaciones reales (avisos, reemplazabilidad, fuente de la biblioteca). Y usar código de
Mornye o de BitChord metería obligaciones de GPL-3.0 sobre toda la app.

### 1.3 La salida: implementación propia desde la especificación

Un decodificador FLAC es un formato documentado (RFC 9639). Implementar un formato desde su
especificación no copia código de nadie: es el mismo caso que escribir un reproductor de MP3.
Por eso lo que se agregó es:

- **Cero dependencias** (nada de libFLAC, nada de libwavpack, nada de FFmpeg para FLAC).
- **Sin código derivado**: sólo la especificación, más libsndfile/libFLAC usado *solamente*
  como **oráculo de pruebas en el host** (no se enlaza en la app, no se distribuye).

Si alguna vez se quisiera portar literalmente un decodificador LGPL, el proyecto tendría que
relicenciarse y cumplir las cuatro obligaciones de la tabla de arriba. Hoy no hace falta.

---

## 2. Por qué hacía falta (el problema de 24 bits)

El camino anterior de FLAC era Media3 → `MediaCodec` / FFmpeg:

- `c2.android.flac.decoder` (el decoder de plataforma) emite **16 bits** o **float**. No hay
  camino de 24 bits enteros.
- El camino float de Media3 aplica `ToFloatPcmAudioProcessor`, que multiplica por
  `1/0x7FFFFFFF`. Un `int24` de 24 bits convertido a float de 32 bits con 24 bits de mantisa
  **pierde los bits bajos** y, al volver a entero, no coincide con el archivo.

Es decir: no era un problema de "calidad audible", era que **el bit-perfect se rompía antes
del DAC**, y la app reportaba bit-perfect igual. Con un decodificador propio que entrega
`int16`/`int24`/`int32` intercalados, `AudioEngine` escribe exactamente lo que estaba en el
archivo.

## 3. Qué implementa `FlacDecoder`

Archivo: `app/src/main/cpp/audio/FlacDecoder.h` + `.cpp` (~1.000 líneas, sin dependencias).

- **Contenedor**: firma `fLaC`, salto opcional de etiqueta ID3v2, bloques de metadatos:
  `STREAMINFO`, `SEEKTABLE` (búsqueda exacta) y salto del resto (`VORBIS_COMMENT`,
  `PICTURE`, `PADDING`).
- **Cabecera de frame**: sync de 14 bits, blocking strategy, códigos de bloque / rate /
  profundidad / asignación de canales, número de frame/sample en UTF-8 extendido y
  **CRC-8 verificado de verdad** (tabla propia, polinomio 0x07).
- **Subframes**: `CONSTANT`, `VERBATIM`, `FIXED` (órdenes 0-4), `LPC` (órdenes 1-32 con
  coeficientes y shift), bits desperdiciados.
- **Residual**: Rice particionado, métodos 0 y 1, con escape a residual sin codificar.
- **Estéreo**: izquierda/derecha, izquierda/diferencia, diferencia/derecha, medio/lateral.
- **CRC-16 de frame verificado** (polinomio 0x8005, init 0). Todos los bytes que consume el
  lector pasan por una única puerta (`pullByte`), así que ningún camino puede olvidarse de
  alimentar el CRC.
- **EOF y corrupción**: `readUnary` tiene tope (un archivo truncado no cuelga el hilo de
  audio) y hay modo estricto / tolerante para el CRC.
- **Índice de frames** (`buildFrameIndex`): recorre *sólo cabeceras* (validando CRC-8 y
  encadenando por el tamaño de bloque) para poder buscar en archivos **sin SEEKTABLE** sin
  decodificar desde el principio.
- **Reapertura en medio del archivo** (`openFrom`): Media3 reabre el `DataSource` después de
  un seek; esto reanuda sin volver a leer metadatos.

Salida: PCM intercalado con signo en el ancho nativo (16 → `int16`, 24 → 3 bytes LE,
32 → `int32`). Sin conversión posterior: es el mismo buffer que `AudioEngine` escribe.

## 4. Verificación (esto es lo importante, no la prosa)

`tools/flac-tests/run.sh` genera los archivos de prueba con **libsndfile** (que usa
**libFLAC** por dentro), decodifica el PCM de referencia con esa implementación
independiente y compara **byte a byte** contra lo que entrega el decodificador propio.

Señales de prueba, a propósito para ejercitar todos los caminos: rampa (predictor perfecto →
`FIXED`/`LPC` corto), dos tonos + ruido (LPC de orden alto), silencio digital (subframe
`CONSTANT`) y ruido a fondo de escala (`VERBATIM`/Rice con parámetro alto).

Cobertura: 16 y 24 bits × 44.1 / 48 / 96 kHz × mono y estéreo (8 archivos).

Por cada archivo el arnés comprueba:

1. metadatos (rate, canales, profundidad, duración, samples totales);
2. **decodificación completa byte a byte** (0 diferencias);
3. `seek()` a 1/3 de la pista contra la lectura secuencial;
4. `openFrom()` en **cada** entrada del índice de frames, comparando el PCM contra el
   esperado (es la ruta que usa el seek y la reapertura de Media3);
5. el índice cubre el archivo completo (suma de bloques == total de samples);
6. un byte dañado en el medio **no pasa inadvertido**;
7. no hay frames fantasma después del final.

Resultado:

```
flac-tests normal : 428 comprobaciones, 0 fallas
flac-tests --asan : 428 comprobaciones, 0 fallas   (AddressSanitizer + UBSan)
flac-tests --tsan : 428 comprobaciones, 0 fallas   (ThreadSanitizer)
```

Bugs reales que encontró el arnés (y que quedaron corregidos):

- **Mono leía fuera de rango**: la decorrelación estéreo tomaba `channels_[1]` aunque el
  archivo fuera mono. Lo detectó ASan.
- **CRC-8 y CRC-16 no se verificaban**: se leían y se tiraban (el CRC-8 incluso se leía con
  un truco de "leer 2 y retroceder 1"). Ahora se calculan con tablas y se comparan.
- **`position()` relativa**: un lector nuevo creado después de un seek informaba posiciones
  desde cero, así que el índice de frames se armaba mal (todas las entradas apuntaban al
  primer frame).
- **`framesDecoded_` derivado del número de frame**: en blocking fijo, el último bloque (más
  corto) hacía que la cuenta se pasara del final del archivo y la lectura terminaba antes.
- **Seek con overshoot**: la cuenta de `frameCursor_` estaba invertida.
- **ID3v2**: se consumían 10 bytes y se releían 4 (el lector quedaba 6 bytes adelantado).

## 5. Cómo entra en la cadena de reproducción

```
MediaItem(.flac local)
   └─ LocalFlacMediaSourceFactory        elige por pista (FLAC local vs. todo lo demás)
        └─ ProgressiveMediaSource
             └─ FlacPcmExtractor        decodifica: entrega PCM entero (audio/raw 16/24/32)
                  └─ FlacDecoderBridge  JNI → FlacDecoder (C++)
                       └─ AudioSink de Media3 → EqAudioProcessor → OboeAudioOutput → DAC
```

Decisiones y por qué:

- **Se decodifica en el extractor, no en un `Renderer` propio.** Media3 ya sabe llevar
  `audio/raw` con `pcmEncoding` de 24 bits hasta el `AudioSink`; escribiendo un renderer
  habría que reimplementar buffers, timestamps, drenado y sincronización. El extractor
  entrega PCM y el resto del pipeline (el que ya funciona) hace lo suyo.
- **Un bloque de FLAC = un sample de Media3.** Así un seek por `sampleTime` cae siempre al
  inicio de un bloque decodificable.
- **Posición del `DataSource` sincronizada.** Los bytes se leen del `FileDescriptor` (el
  decodificador necesita acceso aleatorio) y después se salta en el `ExtractorInput`
  exactamente lo que ocupó el bloque, con `pread` en el lado nativo para que decodificador e
  indexador no compartan el offset del archivo. Si el reproductor reabre el stream, la
  posición informada coincide con la del decodificador y se reabre ahí mismo.
- **SEEKTABLE cuando existe**; si no, índice de frames (submuestreado si el archivo tiene
  más bloques que el tope, para que cubra todo el archivo y no sólo el principio).
- **Se respeta el ajuste `decoder`**: con "MediaCodec" elegido explícitamente, el FLAC local
  vuelve al camino de Media3. Sirve para comparar A/B y para diagnosticar.

## 6. Qué queda afuera (dicho de frente)

- **Nada de esto está validado en un teléfono.** Lo que está verificado es el decodificador
  en el host (bit a bit) y los tipos del lado Kotlin con stubs de Media3. El extractor y el
  `FileDescriptor` no se pueden ejecutar sin un dispositivo o un emulador.
- WavPack y DSD siguen sin decodificador propio.
- Salida USB isócrona directa (libusb/USBDEVFS) sigue fuera: el camino es Oboe → AudioFlinger
  → USB, con la política de exclusividad que ya existe.
- 8 y 12 bits de FLAC (raros) se piden como 16 bits: el motor no tiene camino entero para
  ellos y la UI lo muestra como conversión, no como bit-perfect.
