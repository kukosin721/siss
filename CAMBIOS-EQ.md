# Ronda EQ — ecualizador de 10 bandas + tipografía

Esta es la última tanda que quedaba por portar de la serie vieja a
`feature/ytm-swipe-animado`. Las rondas anteriores (motor Oboe bit-perfect, modos de
salida, fundidos, saltos instantáneos, caché de biblioteca, recuperación de fallos)
ya están en `5247f32`. Acá va el ecualizador **de verdad** (motor DSP + pantalla) y las
fuentes Google Sans.

Lo demás que había en el árbol viejo (dock retráctil, índice alfabético, barra de
avance, letras, orden de lista, browse views) **no se portó a propósito**: es una
reescritura de UI de otra arquitectura y esta rama ya resuelve eso con
`PowerampSections.kt`, `QueueSheet.kt` y `PlayerApp.kt`. Traerlo sería romper lo que
anda, no mejorarlo.

---

## 1. Motor DSP (`app/src/main/java/com/kukosin/app/eq/`)

| Archivo | Qué hace |
|---|---|
| `Biquad.kt` | Coeficientes RBJ (peaking / low-shelf / high-shelf) en **Double**, y `BiquadDesign.responseDb()` que calcula `|H(e^jω)|` de la cascada completa. La curva que ves en pantalla es la respuesta real, no una interpolación de los sliders. |
| `EqEngine.kt` | Motor en tiempo real: 10 etapas biquad en forma directa II traspuesta, aritmética Double, **suavizado de coeficientes cada 64 frames** (nada de zipper noise al arrastrar). Si el EQ está apagado o la curva es plana, **bypass real: no se copia ni un byte**, el buffer sigue de largo. |
| `EqManager.kt` | Singleton. `snapshot` es `@Volatile`: el hilo de audio sólo lee esa referencia, nunca toca `SharedPreferences` ni `StateFlow`. `settings` / `meter` / `clipping` son `StateFlow` para la UI. Persistencia en prefs propias (`kukosin_eq_v1`). `reportPeak()` lo llama el hilo de audio (sólo escrituras volatile), `consumePeak()` lo llama la UI a 20 Hz. `init(context)` es idempotente. |
| `EqModel.kt` | `EqSettings` / `EqBand`, 10 bandas ISO de octava (31 Hz … 16 kHz, shelf en las puntas), límites (±12 dB, Q 0.3–6, 20 Hz–20 kHz), 10 presets, headroom automático (`effectivePreampDb`), `isFlat` / `isProcessing`. |
| `playback/EqAudioProcessor.kt` | `BaseAudioProcessor` de Media3. Mismo formato de entrada y salida (16/24/32 bits y float), un solo DSP en la cadena. |

## 2. Dónde entra en la cadena

`HiFiRenderersFactory` ya no dice "futuro":

```
decoder → EqAudioProcessor (bypass si está apagado o plano) → Oboe → DAC
```

`EqManager.init(this)` se llama en `KukosinApp.onCreate()` y en `PlaybackService.onCreate()`
(idempotente), así el estado existe antes de que se construya el sink.

## 3. Pantalla (`ui/EqualizerWorkspace.kt`)

Reemplaza el mock `PowerampEqualizerWorkspace` que había en `PowerampSections.kt`
(242 líneas de maqueta borradas). Es una sección de pantalla completa, igual que el
resto de las secciones de esta rama: `EqualizerWorkspace(bottomPadding, onBackToPlayer, modifier)`.

Qué tiene:

- Curva de respuesta real con relleno, rejilla de frecuencias y de ±6/±12 dB.
- **Arrastrar sobre la curva** mueve la banda más cercana (detecta la banda por
  distancia en X, no por índice).
- Chips de banda, editor de la banda elegida con ganancia, y (plegable) frecuencia y Q.
- Preamp manual + headroom automático, con aviso explícito si estás reforzando sin
  headroom: *"Refuerzo máximo +X dB sin headroom automático…"*.
- Vúmetro con pico real por buffer e indicador de clip.
- 10 presets.

### Arreglos que hice sobre el original

1. **La rejilla y los pomos estaban corridos.** Se dibujaban con
   `log10(f) / log10(1000) * ancho`, o sea 20 Hz caía al 43 % del ancho y 20 kHz se
   salía de la pantalla, mientras la curva sí usaba la escala correcta (20 Hz → 0 %,
   20 kHz → 100 %). Ahora rejilla, pomos y `nearestBand()` comparten exactamente la
   misma normalización logarítmica que la curva.
2. **Etiquetas**: `formatFrequency()` no trae unidad, así que salía "1k Hz". Ahora es
   "1 kHz" / "62 Hz".
3. **El aviso de clip no se apagaba nunca** (quedaba rojo para siempre). Ahora se
   limpia solo después de ~1,5 s sin picos por encima de −0,45 dBFS.
4. `CURVE_SAMPLE_RATE` usa `EqSettings.REFERENCE_SAMPLE_RATE` (48 kHz) en vez de un
   número suelto duplicado.

## 4. Honestidad con el bit-perfect (esto sí cambia lo que dice la app)

Con el EQ encendido y la curva **no** plana, el PCM pasó por un DSP: ya no es
bit-perfect, aunque el DSP sea de 64 bits. Así que:

- `AudioStatus` ganó `eqActive` (lee `EqManager.snapshot.isProcessing`).
- `bitPerfectNow` ahora es `info.bitPerfect && mode.usesEngine && !builtinSpeaker && !eqActive`.
- Las dos pantallas que muestran el estado (hoja de ruta de señal y Ajustes → Audio)
  dicen **"No · ecualizador activo (DSP de 64 bits)"** en vez de mentir.
- EQ **apagado** o **plano** = bypass real (`isProcessing == false`): sigue bit-exacto.

## 5. Tipografía

`res/font/google_sans_{regular,medium,bold}.ttf` + `ui/theme/Type.kt` con
`GoogleSans` como familia de toda la tipografía.

Nota de licencia, tal cual corresponde: Google Sans es propietaria de Google. Para un
proyecto personal no hay drama; si la app se publica, se reemplazan los `.ttf` por una
alternativa abierta (Manrope, Inter, Poppins) y **sólo** se toca `Type.kt`
(`val GoogleSans = FontFamily(...)`).

## 6. Tests

`app/src/test/java/com/kukosin/app/EqFilterTest.kt`, 18 casos (JVM puro, sin Android):

- campana: pega exacto en el centro, simétrica al cortar, transparente lejos del centro;
- 0 dB = bypass exacto;
- low/high shelf afectan sólo de su lado;
- preset plano no cambia la curva;
- el headroom automático compensa el pico real de la curva y la cascada nunca pasa de 0 dB;
- el motor es bit-perfect (bypass) apagado o plano, aplica el refuerzo pedido y queda finito;
- el motor se doma solo cuando hay headroom automático;
- las bandas por defecto son ISO con shelves en las puntas;
- cambiar una ganancia marca el preset como "Personalizado";
- la ganancia se recorta al rango permitido.

```bash
./gradlew testDebugUnitTest --tests "com.kukosin.app.EqFilterTest"
```

Y no quedó sólo escrito: en el sandbox **bajé el compilador de Kotlin 2.2.10 y JUnit 4.13.2,
compilé `eq/` (sin `EqManager`, que necesita Android) y corrí los tests de verdad**.
Resultado: `EqFilterTest` (18) + `FftTest` (6) = **24 tests, todos en verde**, sin warnings
de compilación del motor. La UI no se puede compilar acá (necesita el SDK de Android),
pero la matemática del EQ y la FFT están verificadas ejecutándose.

## 6.b Perfiles por salida (nuevo)

Antes había **una** curva para todo. Ahora hay tres perfiles, cada uno con su propio
estado (encendido, preamp, bandas, tipo de filtro y preset):

| Perfil | Cuándo se activa |
|---|---|
| `Altavoz` | salida interna del teléfono (`OutputDevice.isBuiltin`) |
| `Auriculares / línea` | jack, USB-C sin DAC propio, cualquier salida que no sea altavoz ni USB |
| `DAC USB` | `isUsb` o `isDac` reportado por `AudioRoute` |

- La app **cambia de perfil sola** cuando cambia la salida (`LaunchedEffect` sobre
  `AudioRoute.current`); no hay que tocar nada.
- Se persiste por perfil, con claves con prefijo (`usb_gain_0`, `wired_type_3`, …).
- **Migración**: si venías del formato viejo (una sola curva sin prefijo), se copia
  una vez al perfil de auriculares. Nadie pierde lo que ya había configurado.
- El hilo de audio no cambia: sigue leyendo `EqManager.snapshot`, que ahora apunta al
  perfil activo.
- La pantalla del EQ muestra el perfil activo en el subtítulo del encabezado
  ("10 bandas · Q constante · 64-bit · DAC USB").

## 6.c Tipo de filtro por banda (nuevo)

En el editor de banda (plegable "Frecuencia y Q de la banda") ahora hay un selector
**Shelf graves · Campana · Shelf agudos**. Al cambiarlo se ajusta la Q a un valor
razonable para ese tipo (1,41 para campana, 0,71 para shelf) y el preset pasa a
"Personalizado". `EqSettings.withBandType()` es el que hace el trabajo.

## 6.d Verificación sin Android (`tools/`)

`EqManager` no depende de nada del sistema salvo `SharedPreferences`, así que se puede
probar de verdad con stubs mínimos:

- `tools/android-stubs/android/content/{Context,SharedPreferences}.java`
- `tools/EqProfileHarness.kt` — banco de pruebas con un `SharedPreferences` en memoria
- `tools/check-eq.sh` — compila todo y lo corre

Qué verifica el banco (14 comprobaciones, todas en verde):

1. la migración del formato viejo copia `enabled`, preamp y ganancias al perfil de
   auriculares;
2. cambiar a USB arranca ese perfil plano, sin tocar el de auriculares;
3. aplicar ganancia y tipo de banda escribe en el perfil activo;
4. `snapshot` (lo que lee el hilo de audio) sigue al perfil activo en los dos sentidos;
5. el altavoz tiene su propio estado en cero;
6. al volver a USB, su curva está intacta;
7. las claves persistidas quedan con el prefijo correcto y no se pisan entre perfiles.

```bash
KC=/ruta/a/kotlinc COROUTINES=/ruta/a/kotlinx-coroutines-core-jvm.jar tools/check-eq.sh
```

## 7. Qué probar en el teléfono (no pude compilar acá)

En el sandbox no hay JDK 17 ni SDK de Android, así que esto se verifica en tu build:

1. Ajustes → Ecualizador abre la pantalla nueva (no el mock).
2. Prender el switch y subir una banda: se escucha el cambio **sin clics** (suavizado
   de 64 frames).
3. Arrastrar sobre la curva: la banda que se mueve es la que está debajo del dedo, y
   los pomos quedan alineados con las líneas verticales de frecuencia.
4. Apagar el EQ (o poner todo en 0 / preset "Plano"): Ajustes → Audio vuelve a decir
   bit-perfect y el sonido no cambia.
5. Con EQ activo: Ajustes → Audio y la ruta de señal dicen "No · ecualizador activo".
6. Presets: aplicar "Bajos" con headroom automático no clipea (el vúmetro no se pone rojo).
7. Cerrar y abrir la app: el EQ recuerda estado, preamp, ganancias y preset.
8. **Perfiles**: con auriculares puestos, subí los graves y fijate que el subtítulo
   diga "Auriculares / línea". Desenchufá (o pasá al altavoz): el EQ tiene que cambiar
   a "Altavoz" con su propia curva, y al volver a enchufar tiene que volver la tuya.
9. **Tipo de filtro**: en el editor de banda, tocá "Shelf graves" y mirá cómo cambia la
   forma de la curva en el extremo.
10. Todo lo que ya andaba sigue andando: animación swipe de YTM, mini player ↔ player,
   secciones estilo Poweramp, cola (`QueueSheet`), slider de onda, resampler sinc, USB DAC.

## 8. Pendientes conscientes (nada de humo)

- **Medición de sala / EQ paramétrico libre** (más bandas, curvas dibujadas a mano):
  proyecto aparte, no un ajuste.
- **Perfiles manuales con nombre propio**: hoy son tres automáticos por tipo de salida.
  Si querés "mis auriculares del laburo" y "los del auto" con nombre, es otro paso.
- El recorte de bits ("el archivo tiene 24: el decoder está recortando") depende del
  decoder elegido: la app lo informa, no lo arregla.
- Sigue sin compilarse la app completa acá (no hay SDK de Android); ver 6.d para lo
  que sí se ejecutó.
