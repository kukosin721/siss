# Rondas 1 a 3 en `feature/ytm-swipe-animado` — ítem por ítem

Las 25 secciones de la serie vieja, una por una: qué se portó, qué ya estaba en esta
rama y qué **no** se portó y por qué. Nada de "hice todo": si algo no aplica, está
dicho acá.

Los commits de esta tanda son:

| Commit | Qué trae |
|---|---|
| `bb11451` | Letras: motor nuevo (ronda 1, ítem 1) |
| `2782b7e` | Orden de la lista, índice alfabético y búsqueda con foco (rondas 2–3) |
| `530f8e8` | Barra de avance táctil del mini reproductor (ronda 2, ítem 10) |
| `5f14f17` | Espectro con detalle, tests de FFT y de la firma de audio, filas animadas |
| `426c9e9` | Fuga del `MediaMetadataRetriever` y `onTaskRemoved` (ronda 1, ítems 5 y 6) |

---

## 1. Letras: motor nuevo — **portado**

`ui/LyricsPanel.kt` es un archivo propio ahora (salió de `TrackStudio.kt`, que quedó
con análisis + espectro). Lo que cambió de verdad:

- **Reloj a 60 fps** (`rememberLyricsClock`): extrapola con `withFrameNanos` desde la
  última posición real y se re-ancla con cada muestra. Se lee **en la fase de dibujo**,
  así que animar no cuesta re-composiciones. Si el reproductor está en pausa, el reloj
  se congela; si detecta un salto de más de 400 ms, se corrige de una.
- **Los otros versos se acomodan**: ease-out largo de 900 ms (`CubicBezierEasing(0.22,
  0.08, 0.16, 1)`), sin desenfoque (era caro con texto grande y se sentía peor).
- **La lista sigue al verso activo, centrado** en el 46 % del alto visible, con un
  `tween` de 950 ms propio (`animateCenteredOn`), no el scroll nativo.
- **No te mueve mientras leés**: al arrastrar se pausa el seguimiento y vuelve solo
  1,8 s después de soltar.
- **Karaoke continuo**: cada palabra usa su duración real (hasta la siguiente) y el
  relleno dorado se calcula en la fase de dibujo; las palabras ya cantadas quedan
  doradas en vez de cambiar de golpe.

`PlayerApp` le pasa además `isPlaying`, que el reloj necesita para no correr en pausa.

## 2. Tipografía Google Sans — **ya entregado** (commit `179fbad`)

## 3. Animaciones de navegación — **parcial, a propósito**

- **Hecho**: las filas de la lista usan `Modifier.animateItem()`, así filtrar o
  cambiar el orden **desplaza** las filas en vez de hacerlas saltar.
- **No hecho**: `AnimatedContent` entre pestañas de biblioteca. Esta rama no tiene
  pestañas: tiene **secciones** (`AppSection`) con su propia transición
  (`animatePlayerSheet`, morphing de carátula). Meter otra capa encima sería pelearse
  con la animación que ya funciona y que pediste cuidar.

## 4. Espectro con más detalle — **hecho**

- `AudioAnalyzer.SPECTRUM_BANDS = 288` (antes 96).
- `SpectrumChart`: 210 dp de alto (antes 150), barras redondeadas con opacidad según
  intensidad, rejilla cada 20 dB, marca de 20 kHz y referencias de eje 0 Hz / Nyquist.

## 5. Reproducción: dos bugs reales — **casi todo ya estaba**

- `configState.drop(1)` y el filtro por `audioSignature`: **ya estaban** en esta rama.
- Cancelación de `settingsJob`/`retryJob` en `onDestroy`: **ya estaba**.
- **Agregado**: `onTaskRemoved`. Si sacás la app de recientes y **no hay nada
  cargado** en el reproductor, el servicio se apaga. Si hay cola (aunque esté en
  pausa) no se toca, porque puede volver desde la notificación o Android Auto.

## 6. Biblioteca más rápida — **ya estaba, más un arreglo**

- Escaneo en paralelo + caché persistente (`PROBE_CONCURRENCY`, `LibraryCacheStore`):
  vinieron en el commit de audio.
- Carátula embebida como respaldo (`data/artwork/EmbeddedArtwork.kt`): ya la tenía
  esta rama.
- **Arreglado en esta tanda**: `MediaMetadataRetriever` se liberaba sólo si todo
  salía bien. Si `setDataSource` lanzaba, quedaba un retriever vivo **por archivo**.
  Ahora hay `try { … } finally { release() }`.

## 7. YouTube Music honesto — **ya estaba**

Esta rama no usa Piped: usa `NewPipeDownloader` y resuelve el audio de verdad antes de
encolar, con `describeYtmError` para explicar el fallo en castellano. No había nada
que traer.

## 8. Orden interno (partir `PlayerApp`) — **hecho**

`PlayerApp.kt` tenía **3.963 líneas**. Quedó partido en cinco archivos, todos en el
mismo paquete `com.kukosin.app.ui` (así no hubo que tocar imports entre archivos):

| Archivo | Líneas | Qué tiene |
|---|---|---|
| `PlayerApp.kt` | 1.062 | raíz (`PlayerApp`), estado de reproducción, `PlaybackSnapshot`, arranque de la cola |
| `LibraryViews.kt` | 991 | buscador, lista de canciones y su fila, álbumes/artistas/carpetas, YT Music, estados vacíos |
| `PlayerViews.kt` | 1.168 | mini reproductor, reproductor grande, carátula compartida y morphing, interpoladores |
| `Sheets.kt` | 857 | ruta de señal, detalle de álbum/artista, inspector de metadatos |
| `SettingsScreen.kt` | 895 | ajustes completos y utilidades de permisos |

Las funciones de nivel superior pasaron de `private` a `internal` para poder cruzarse
entre archivos. Se verificó: balance de llaves/paréntesis en los cinco archivos, cero
declaraciones duplicadas en el paquete y cero errores de sintaxis compilando el paquete
`ui/` completo con `kotlinc` (los *unresolved reference* son esperables: sin el SDK de
Android no hay Compose).

## 9. Tests — **ampliados**

- `FftTest` (nuevo): tono puro con pico en `k` y en `N−k`, señal constante = sólo DC,
  dos tonos conservan su altura, y tamaños inválidos (no potencia de 2, tamaños
  distintos, 1 muestra) se rechazan.
- `AudioConfigTest` (nuevo): regresión de `audioSignature` — los ajustes de la cola y
  del análisis **no** deben cortar la reproducción; los de audio sí deben cambiarla.
- `LibrarySortTest` (nuevo): los cinco criterios de orden, el índice alfabético y el
  recorte de tildes.
- `EqFilterTest` (15 casos) y `LyricsParseTest`: ya estaban.

```bash
./gradlew testDebugUnitTest
```

## 10. Barra de avance nueva — **hecha en el mini reproductor**

El reproductor grande de esta rama usa el **slider de onda** (`KukosinWaveformSlider`),
que es parte de lo que pediste cuidar: no lo toqué.

Lo que sí faltaba: el mini reproductor tenía una `LinearProgressIndicator` que **no se
podía tocar**. Ahora usa `CompactSeekBar` (`ui/PlaybackSeekBar.kt`):

- toque = salto directo; arrastre = scrubbing continuo;
- píldora dorada con el tiempo exacto al que vas, siguiendo al dedo;
- pulgar con halo y algo más grande al arrastrar;
- el progreso se interpola con un `tween` lineal de 420 ms, así se mueve suave entre
  las muestras del reproductor (~400 ms) en vez de avanzar a saltos.

## 11. Mini reproductor dentro de las letras — **no aplica**

En esta rama las letras se abren **dentro** del reproductor grande, que ya tiene
controles y la barra de onda. Agregar un mini reproductor ahí adentro sería duplicar
controles en la misma pantalla.

## 12. Tocar la carátula = ir a la biblioteca — **hecho**

- Tocar la carátula ahora **cierra el reproductor, abre Biblioteca → Todas las
  canciones y desplaza la lista hasta la pista que está sonando**, que queda
  **resaltada en dorado** un par de segundos y se apaga sola.
- Las letras siguen a un toque: hay un botón **"Ver letras"** debajo del chip de
  calidad, y el gesto de deslizar hacia arriba sigue igual.
- El texto de ayuda de la carátula y la descripción de accesibilidad dicen lo que
  pasa ahora, sin mentir.
- El resaltado no se come el resto de la fila: es una capa dorada con `alpha`
  animada y se limpia a los 1,9 s.

## 13. Búsqueda con teclado inmediato — **hecho**

`SearchField` ahora puede pedir foco y mostrar el teclado al abrirse. Se activa **sólo
en la pantalla de búsqueda** (`autoFocus = true`); en las listas el campo sigue siendo
un filtro silencioso, que es lo correcto. Además tiene la **X** para limpiar la
consulta.

## 14. Índice alfabético lateral — **hecho**

`ui/AlphabetIndex.kt`: columna A–Z pegada al borde derecho de la lista de canciones.

- Arrastrás el dedo y la lista salta a la primera canción de esa letra.
- La letra activa aparece en un **círculo dorado** en el medio de la pantalla.
- Sin tildes (`Á` → `A`), `#` para números y símbolos.
- Sólo muestra las letras que existen en la lista actual (respeta la búsqueda y el
  orden elegido) y **se oculta** cuando el orden no es alfabético (duración/año) o la
  lista es corta.

## 15. Dock inferior con retracción — **hecho, adaptado**

En esta rama no hay un dock de cuatro botones abajo de la biblioteca: esos accesos
(`PlayerShortcutBar`) viven dentro del reproductor, y lo que flota abajo en la
biblioteca es el **mini reproductor**. Así que la retracción se aplicó ahí:

- Al **bajar** en la lista (140 dp acumulados de scroll del usuario) el mini reproductor
  **se va hacia abajo y se desvanece** (`offset` animado 240 ms + alpha 200 ms).
- Al **subir** vuelve al toque (umbral bajo, 50 dp): si querés el reproductor, aparece
  con un gesto corto.
- Al **cambiar de sección** vuelve siempre, y sólo se retrae en **Biblioteca**
  (en Ajustes o en el EQ esconderlo al scrollear sería molestar, no ayudar).
- Usa `Modifier.offset { }` en fase de layout y no `graphicsLayer`: así la medida de la
  carátula que alimenta el morph mini → reproductor sigue siendo la real.
- **Lo que no hice**: la fila de acciones rápidas (aleatorio · play · buscar ·
  seleccionar) que aparecía en lugar del mini en la versión vieja. En esta rama esas
  acciones ya están en el reproductor y duplicarlas abajo sumaba ruido. Si las querés,
  se agregan.

## 16. Orden de la lista — **hecho**

`ui/LibrarySort.kt` con cinco criterios: título, artista, álbum, duración y año.

- El orden se aplica **en el ViewModel**, no en la pantalla: la lista que ves y la que
  se manda al reproductor son la misma, así que la canción que tocás es la que suena.
- Se elige desde el botón de orden en la cabecera de canciones y **queda guardado**
  entre sesiones.
- Corre en `Dispatchers.Default`: con 10.000 canciones, ordenar en cada tecla de la
  búsqueda se sentiría en la UI.
- Honestidad sobre el criterio "año": esta rama no guarda la fecha en que se agregó el
  archivo, así que ordena por el año del tag (y número de pista). Por eso se llama
  **"Año"**, no "Recientes".

## 17. Gestos en el mini reproductor — **ya estaba, y mejor**

`CoverGesture.kt` + `PlayerGestures.kt` ya resuelven eje único por gesto: horizontal =
cambiar de tema, arriba = letras, abajo = minimizar, con umbral y vuelta elástica. La
versión vieja sólo hacía izquierda/derecha. No lo toqué.

## 18. Ecualizador — **entregado** (`179fbad`) **y ampliado**

Motor de 10 bandas en 64 bits, pantalla nueva, presets, headroom automático, vúmetro,
y el arreglo del bug de escala de la rejilla. `EqFilterTest` con 18 casos.

Ampliado en esta tanda: **perfiles por salida** (altavoz / auriculares / DAC USB, se
cambian solos y cada uno guarda su curva) y **tipo de filtro editable por banda**
(shelf graves, campana, shelf agudos). Detalle y verificación en `CAMBIOS-EQ.md`.

## 19. Remuestreador — **ya estaba**

`playback/SincResampler.kt` (FIR polifásico) y el motor nativo en C++ que lo aplica
antes del ring.

## 20 a 24. Motor de audio, modos de salida, fundidos, saltos y pendientes — **entregados** (`5247f32`)

Oboe bit-perfect, cuatro modos de salida, fundidos configurables (switch en Ajustes),
saltos instantáneos, caché de biblioteca, recuperación automática de la salida,
selector de decodificador con la verdad de bits y aviso de xrun.

## 25. Primera compilación real — **pendiente tuya**

No hay SDK de Android en el sandbox, así que **la app completa no la compilé**. Pero
sí bajé el compilador de Kotlin 2.2.10 y JUnit 4.13.2 y **compilé y corrí de verdad** la
parte que no depende de Android: `eq/Biquad.kt`, `eq/EqModel.kt`, `eq/EqEngine.kt`,
`data/analysis/Fft.kt` y sus tests → **21 tests en verde** (15 de EQ + 6 de FFT). Todo lo que
puedo verificar sin compilador está verificado: balance de llaves/paréntesis en los 60
archivos Kotlin, cero declaraciones duplicadas en el paquete `ui`, firmas y llamadas
alineadas, y los patches aplican limpio en un clon vacío.

---

## Qué probar en el teléfono

1. **Letras**: abrí una canción con letras sincronizadas. El relleno palabra por
   palabra tiene que correr continuo (no a saltos de 200 ms) y la lista sigue al verso
   sin tironear. Arrastrá la lista: se frena y vuelve sola a los ~2 s.
2. **Búsqueda**: tocá la lupa → el teclado sale solo. La X limpia el campo.
3. **Orden**: en Canciones, botón de orden → probá Artista y Año. El índice A–Z
   aparece con los tres alfabéticos y desaparece con Duración/Año. Cerrá y abrí la app:
   el orden elegido sigue ahí.
4. **Índice**: arrastrá el dedo por la columna de letras; la burbuja dorada aparece en
   el centro y la lista salta.
5. **Mini reproductor**: tocá y arrastrá la barrita de arriba del mini reproductor.
   Tiene que aparecer la píldora con el tiempo y saltar ahí al soltar.
6. **Espectro**: analizá un archivo; el gráfico es más alto, con rejilla y marcas.
7. **Tests**: `./gradlew testDebugUnitTest`.
8. **Lo que ya andaba**: animación swipe de YTM, mini ↔ player, secciones Poweramp,
   cola, slider de onda, resampler, USB DAC, EQ.

## Pendientes conscientes

- **Compilar la app entera**: no hay SDK de Android en el sandbox. Lo que sí se
  verificó está en la sección de verificación; si Gradle se queja, con el log lo
  arreglo al toque.
- **Ítem 15**: la retracción quedó en el mini reproductor (ver arriba). Las acciones
  rápidas que en la versión vieja ocupaban su lugar no se agregaron.
- Perfiles del EQ: quedaron **tres** (altavoz / auriculares / DAC USB) y cambian solos.
  Si querés perfiles manuales con nombre propio, es otro paso.

---

## Cómo compilar esto

La carpeta **`repo/`** *es* el proyecto de Android completo (raíz con `gradlew`,
`settings.gradle.kts`, `build.gradle.kts` y `app/`). No hay que armar nada:

```bash
# Opción 1: Android Studio
#   File → Open → elegí la carpeta repo/  (no app/, no el zip)
#
# Opción 2: consola
cd repo
./gradlew assembleDebug        # APK de debug
./gradlew installDebug         # compila e instala en el teléfono conectado
./gradlew testDebugUnitTest    # los tests JVM (EQ, FFT, orden, audio, gestos)
```

Datos del proyecto tal como está en esta rama:

| Cosa | Valor |
|---|---|
| Gradle | 9.6.0 (wrapper incluido, no hace falta instalarlo) |
| AGP | 9.4.1 |
| Kotlin | 2.2.10 · Compose BOM 2026.02.01 |
| compileSdk / targetSdk | 37 · minSdk 24 |
| JDK del daemon | 25 (lo baja solo con el toolchain de foojay) |

Android Studio te crea el `local.properties` con la ruta del SDK la primera vez; si
compilás por consola, acordate de tenerlo (`sdk.dir=/ruta/al/Android/Sdk`).

Si en cambio querés **aplicarlo sobre tu clon** de `feature/ytm-swipe-animado`:

```bash
git checkout feature/ytm-swipe-animado
git am /ruta/cambios-rondas-1-a-3.patch      # sólo las rondas 1-3 (sobre el commit del EQ)
# o, desde la base 9f2b50c, todo de una:
git apply --binary /ruta/cambios-total-feature.patch
```
