# Motor de audio nuevo (rondas 5–6) — portado a `feature/ytm-swipe-animado`

Este documento describe el trabajo que se aplicó sobre esta rama. Reemplaza la
maquinaria de salida vieja (`AudioTrack` / `OpenSL ES` / "Hi-Res Direct" / driver
selector / rate objetivo / formato PCM forzado) por un motor de Oboe propio con
bit-perfect real, y agrega lo que se pidió después: modos de salida, fundidos,
saltos sin cortes y caché de biblioteca.

> **Contexto**: el trabajo se había hecho sobre otra rama/commit. Acá está portado a
> `feature/ytm-swipe-animado` respetando su arquitectura (PlayerApp, PowerampSections,
> AudioRoute, PlaybackService, UsbDacManager, etc.), no copiado a ciegas.

---

## 1. Motor nativo (C++ / Oboe)

`app/src/main/cpp/audio/AudioEngine.{h,cpp}` + `native-lib.cpp` reescritos.

- Un stream de Oboe abierto **exactamente** con el formato de la pista: rate, canales
  y profundidad. Escalera de apertura: exacto + exclusivo + `PerformanceMode::None`
  → exacto (compartido, o exclusivo si el modo lo exige) → conversión propia sólo si
  "Bit-perfect estricto" está apagado.
- `setFormatConversionAllowed(false)`, `setChannelConversionAllowed(false)`, SRC `None`.
- Callback de **copia pura** (`memcpy`) con anillo SPSC lock-free (256 KB–4 MB).
- Resampler Kaiser polifásico (512 fases, β=8) del lado del productor: la conversión
  nunca ocurre en el callback.
- Posición derivada de los frames realmente renderizados.
- `StreamInfo` expone lo que devolvió el hardware (api, formato, rate, exclusivo,
  passthrough, xruns, volumen, error), no lo que pedimos.

## 2. Modos de salida (los cuatro de bitchord)

`playback/OutputMode.kt`. No son "drivers": son caminos con semántica distinta.

| Modo | Camino | Volumen | Bit-perfect |
|---|---|---|---|
| `DISABLED` — Desactivado | mezclador de Android (AudioTrack) | sistema | nunca |
| `DIRECT_DRIVER` — DAC integrado · controlador directo | exclusivo + volumen en el hardware (pide permiso USB si hay DAC) | en el DAC | con DAC |
| `AAUDIO_EXCLUSIVE` — DAC integrado · AAudio exclusivo | exclusivo sobre jack/línea, rate de cada pista | digital | sólo al 100 % |
| `USB_DAC` — DAC USB | exclusivo, rate **y** profundidad de cada pista | fijo al 100 %, se regula en el DAC | sí |

**El altavoz interno no puede ser bit-perfect** y la UI lo dice: `AudioStatus.bitPerfectNow`
exige exclusivo + sin conversión + volumen 100 % + que la salida no sea el
altavoz/auricular del teléfono.

## 3. Fundidos (sin clics)

En el callback nativo, porque es el único lugar con el tiempo real:

| Petición | Quién la emite | Qué hace el callback |
|---|---|---|
| `kFadeIn` | `start()` (play / reanudar) | rampa 0→1 en 25 ms |
| `kFadeOutAndStop` | `pause()` / `stop()` | rampa 1→0 en 60 ms y luego `DataCallbackResult::Stop` |
| `kFadeRearm` | `flush()` (salto, seek, cambio de pista) | vuelve a 0 y entra en 12 ms |

- La parada la pide el callback (`Stop`), no el hilo de control: así el fundido se
  escucha completo.
- Durante el fundido sólo se consumen esos milisegundos; el resto del anillo queda para
  cuando se reanude.
- Cuando la ganancia vale exactamente 1.0 la rampa ni se ejecuta: el callback vuelve a
  ser `memcpy` puro y el bit-perfect no se toca.
- Interruptor en Ajustes → Audio (`AudioConfig.fades`), prendido por defecto.

## 4. Motor rápido: saltos instantáneos

El problema no era Oboe, era **dónde estaba el buffer**:

| Capa | Antes | Ahora |
|---|---|---|
| Buffer de hardware (AAudio) | tamaño del ajuste (~250 ms) | **2 ráfagas** (~10–20 ms) |
| Buffer interno (anillo) | — | 4 × `bufferMs` (ajuste del usuario) |

En cada salto se tiraba y rellenaba hasta 250 ms de audio ya entregado al dispositivo:
ese era el microsilencio. Ahora la estabilidad vive en el anillo (memoria propia, vaciarlo
es gratis) y el hardware se queda en el mínimo.

Además:
- **Prerról**: `start()` no arranca en seco; arranca cuando el anillo tiene 2 ráfagas o
  20 ms. Antes arrancaba vacío = silencio + xruns.
- **Fundido de salto corto** (12 ms) en vez del de 25 ms del play normal.

Límite honesto: si las dos pistas tienen **distinta frecuencia** hay que reabrir el
stream (~10–30 ms de AAudio + 20–100 ms del DAC reenganchándose al rate nuevo). Es
hardware. Lo que sí se quitó es el corte audible y los 250 ms propios.

## 5. Caché de biblioteca persistente

`data/LibraryCacheStore.kt` + hooks en `MusicLibrary.enrich`:

- Lo que costó leer de cada archivo (duración, sample rate, profundidad, etiquetas) se
  guarda en `filesDir/library_probe_cache.json`, atómico (tmp + rename), tope 60.000
  entradas, escritura cada 10 s como máximo, sondeo con 4 hilos.
- "Volver a escanear" tira la caché en memoria **y** en disco (`viewModel.rescan()`).

## 6. Recuperación de la salida de audio

- `PlaybackService` ahora reconstruye el player **sólo si cambió algo que afecta al
  audio** (`audioSignature`). Antes se recreaba con cualquier ajuste de `AudioConfig`
  —incluido el aspecto de la cola— y eso cortaba la reproducción.
- Si el player falla por la salida de audio (DAC desenchufado, stream exclusivo
  rechazado, modo DAC USB sin DAC), se reintenta **una vez** conservando pista y
  posición, y el motivo queda visible en la cadena de señal.
- Si el stream nativo muere, `write()` ahora lanza `AudioOutput.WriteException` en vez
  de devolver `false` para siempre (que dejaba al sink esperando en silencio).

## 7. UI

- **Ajustes → Audio**: "Modo de salida" (4 tarjetas con descripción y qué hace con el
  volumen), estado real del motor (API, dispositivo, stream, bit-perfect ahora, buffer,
  xruns), permiso USB cuando corresponde, "Bit-perfect estricto", "Fundidos suaves",
  "Buffer de salida", "Decodificador".
- **Cadena de señal**: el paso 2 dice la profundidad **real** que entrega el decoder
  (y avisa si recorta respecto del archivo), el paso 4 pasó de "AudioTrack & mezclador"
  a "Motor Oboe (C++/JNI)" con lo que abrió de verdad, y el paso 5 explica por qué no
  hay bit-perfect cuando no lo hay (modo desactivado, altavoz interno, compartido,
  conversión o volumen).

---

## Pendiente (siguiente ronda)

1. **Ecualizador con DSP real**: esta rama tiene la pantalla del ecualizador como
   maqueta ("interfaz lista para conectar el motor DSP"). Lo que falta es el motor:
   biquads RBJ de precisión doble, 10 bandas ISO, suavizado de 64 frames, bypass
   bit-exacto, headroom automático, presets y el `EqAudioProcessor` enchufado en
   `HiFiRenderersFactory` (ya está el `TODO` marcado ahí).
2. `SampleRateAudioProcessor.kt` / `SincResampler.kt` quedan **sin uso**: el resampling
   ahora lo hace el motor nativo (Kaiser) y forzar un rate objetivo contradice el
   bit-perfect. Se dejan los archivos por si querés comparar, pero no están en la cadena.
3. Gapless entre pistas de igual formato depende de Media3; entre rates distintos manda
   el hardware (ver §4).

## Cómo probar

1. **Desactivado** → debe sonar igual que antes (mezclador de Android).
2. **AAudio exclusivo** con auriculares → cadena de señal: exclusivo, sin conversión,
   bit-perfect al 100 % de volumen.
3. **DAC USB** con un DAC conectado → rate y profundidad de cada pista, sin atenuación
   digital de la app: el volumen de las teclas del sistema termina en el propio DAC o en
   el índice del stream, no en la señal.
4. Pausar/reanudar y saltar rápido: sin clics, sin huecos entre pistas de igual rate.
5. Desenchufar el DAC a mitad de tema: debe volver a sonar por la salida del sistema
   (reintento automático) y decirlo en el panel.
