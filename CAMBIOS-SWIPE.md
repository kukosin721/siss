# Gesto deslizante de carátulas (estilo Apple Music)

## Qué hace

En la pestaña **Reproductor** del `NowPlayingSheet` la carátula se puede arrastrar
horizontalmente y la vecina entra desde el borde opuesto **en la misma proporción que tu
dedo**:

- **Deslizar a la izquierda** → la próxima canción entra por la derecha y toma el lugar.
- **Deslizar a la derecha** → la canción anterior entra por la izquierda.
- Mientras arrastras, la carátula que sale se desenfoca (`blur` hasta 12 dp) y se
  desvanece; la que entra aparece con alpha = progreso.
- Si sueltas sin pasar el umbral, vuelve al centro con un rebote (`spring`).
- En la primera y última canción de la cola el arrastre hacia afuera se siente "gomoso"
  (resistencia del 18 %) y vuelve sola.

## Qué cambió respecto de tu versión

Tu implementación disparaba `controller.seekToNext()` **dentro de `onDrag`** al pasar el
umbral y reseteaba `dragX = 0f` en el acto. Eso tiene dos consecuencias visibles:

1. la canción cambia a mitad del gesto, no al soltar;
2. el offset salta de golpe a cero, así que no hay animación de entrada ni de vuelta.

Ahora el gesto **solo mide**; la decisión se toma al levantar el dedo y se ejecuta con
animación. Se conservó todo el tratamiento visual que ya tenías (blur, fade, la entrante
entrando desde el borde, `trackArtworkUriAt`, el `SwipeToDismissBox` de las filas de la
cola, el botón de letras, etc.).

## Cómo funciona

```
NowPlayingSheet
├── dragOffset: Animatable<Float>        → 0 = centrado, ±ancho = vecina centrada
├── pointerInput { awaitEachGesture }    → mide el arrastre, no cambia de tema
│   ├── snapTo(acumulado)                → la carátula sigue al dedo en tiempo real
│   └── al soltar → commit() o snapBack()
└── capas: entrante (translationX ± ancho) + actual (blur + fade)
```

| Pieza | Valor |
|---|---|
| Detección | `detectHorizontalDragGestures` + `VelocityTracker` (deja el scroll vertical al sheet) |
| Paso | ancho real de la carátula (`artworkSize.toPx()`) |
| Umbral para cambiar | 35 % del ancho (`dragThreshold`, el que ya tenías) **o** fling de 2.2× ancho/seg |
| Si pasa el umbral | `tween(240 ms, FastOutSlowInEasing)` hasta ±ancho → `seekToNext()` / `seekToPrevious()` |
| Si no pasa | vuelve al centro con `spring(dampingRatio = 0.9, StiffnessMediumLow)` |
| Sin pista en esa dirección | resistencia elástica (18 % del arrastre) y vuelve al centro |
| Scroll vertical | el gesto no se reclama: la letra/cola siguen scrolleando normal |

## Una sola fuente de verdad

`dragX` es un único `mutableFloatStateOf`: lo escribe el dedo mientras arrastras y lo escribe
la animación de cierre frame a frame con `animate()` dentro de un `rememberCoroutineScope()`.

La primera versión mezclaba dos estados (`liveDrag` del dedo + un `Animatable` con `isRunning`
para decidir cuál se pintaba). Si el `Animatable` no llegaba a arrancar, la composición seguía
leyendo el valor del dedo y la carátula **quedaba congelada a medio camino**. Se eliminó ese
acoplamiento.

## El detalle que evita el parpadeo

Al soltar, el orden importa:

```kotlin
animateDragTo(-step, tween(240, easing = FastOutSlowInEasing))   // la vecina queda centrada
val previousIndex = controller.currentMediaItemIndex
controller.seekToNext()
if (awaitIndexChange(controller, previousIndex)) {               // ¿cambió de verdad?
    withFrameNanos { }                                          // un frame para repintar
    dragX = 0f                                                  // recién ahora se resetea
} else {
    snapBack()                                                  // el seek no ocurrió: volver
}
```

`MediaController` aplica los comandos de forma asíncrona (cruzan Binder). Si se hiciera
`snapTo(0f)` inmediatamente, durante unos frames se vería **la carátula vieja centrada**:
eso es exactamente el "salto" que se siente mal. `awaitIndexChange()` hace polling corto
(16 ms, máx. 600 ms) hasta que el índice cambia de verdad, y solo entonces resetea el offset.

## Archivos

| Archivo | Cambio |
|---|---|
| `ui/PlayerApp.kt` | `dragOffset` (Animatable), `commit()`, `snapBack()`, `awaitIndexChange()`, `SWIPE_FLING_FACTOR`, `SWIPE_EDGE_RESISTANCE` |

Las vecinas se leen del **`MediaController`** (`getMediaItemAt(index ± 1)`), no de la lista
local, así que el gesto funciona igual si la cola viene de un álbum, de un artista, de una
carpeta o de YouTube Music.

## Cómo probar

1. Reproduce un álbum (para tener vecinas a ambos lados).
2. Abre el reproductor y arrastra la carátula **lentamente**: la vecina debe asomar
   progresivamente desde el borde, no aparecer de golpe.
3. Suelta a mitad de camino: vuelve al centro con un pequeño rebote.
4. Suelta más allá del 35 % (o haz un flick rápido): completa el cambio y la nueva
   carátula queda centrada, sin que reaparezca la anterior.
5. Prueba en la primera y última canción de la cola: el arrastre hacia afuera se siente
   "gomoso" y vuelve, sin cambiar de pista.
6. Dentro del sheet, un arrastre **vertical** debe seguir scrolleando (letra, cola).
   En la lista de la cola, el swipe lateral de las filas sigue igual.

## Ajustes fáciles

Dentro del `pointerInput` de la carátula en `NowPlayingSheet`:

```kotlin
val threshold = artworkWidthPx * 0.35f   // más bajo = más sensible (vive en dragThreshold)
val flingThreshold = step * SWIPE_FLING_FACTOR   // 2.2f; más bajo = los flicks cuentan antes
dragOffset.animateTo(-step, tween(240, easing = FastOutSlowInEasing))  // 240 = duración del commit
val next = accumulatedX * SWIPE_EDGE_RESISTANCE  // 0.18f = resistencia en los bordes
```

El desenfoque y el desvanecido de la carátula saliente están en `artworkBlur` /
`artworkAlpha` (los tuyos, sin tocar).

## Notas

- El gesto vive en la pestaña **Reproductor** del sheet. Si quieres que también funcione
  desde el mini player, hay que abrir el sheet o replicar el pager ahí.
- Con YouTube Music funciona igual: la cola es toda la búsqueda, así que deslizar,
  "siguiente" y autoplay se comportan como con archivos locales. Detalle técnico en
  `CAMBIOS-YTM.md` → "Streaming como cola real".
- No hay vibración al cambiar de pista; si la quieres, es una línea con
  `LocalHapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)` al hacer commit.
