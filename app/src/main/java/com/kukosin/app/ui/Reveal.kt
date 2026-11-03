package com.kukosin.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Aparición escalonada de filas y tarjetas.
 *
 * Es el gesto visual que hace que entrar a una categoría de la biblioteca no sea un
 * "corte" en el que 200 filas ya están ahí, sino que el contenido **se acomode**: cada
 * elemento entra desde abajo con un desfase chico respecto al anterior, así el ojo ve
 * una ola en lugar de un bloque.
 *
 * # Las tres decisiones que importan
 *
 *  - **Sólo `graphicsLayer`.** El efecto se aplica como capa de GPU: no cambia el tamaño
 *    del ítem, así que la lista no re-mide ni re-ubica nada mientras anima. Cualquier
 *    versión de esto con `padding` o `offset` de layout costaría un re-layout por frame.
 *  - **Desfase acotado.** El desfase crece hasta [MAX_STEPS] elementos y ahí se queda.
 *    Sin el tope, bajar hasta la canción 800 en una lista de 10.000 daría un retraso de
 *    casi 18 segundos. Con el tope, cualquier ítem entra en < 0,5 s.
 *  - **Se reinicia solo.** El estado vive en el `remember` del ítem: cuando `LazyColumn`
 *    lo descarta al salir de pantalla y lo vuelve a componer, la animación se repite. Por
 *    eso al scrollear el contenido se va acomodando a medida que aparece, sin tener que
 *    leer el estado del scroll (que obligaría a recomponer cada ítem en cada frame).
 *
 * La velocidad del efecto sigue el ajuste de Ajustes → Animation speed: con "Off" no hay
 * desfase ni animación, y el contenido aparece de golpe como antes.
 */
private const val MAX_STEPS = 8
private const val STEP_MS = 22

/**
 * Envuelve un ítem de lista para que aparezca escalonado según [index].
 *
 * @param index posición del ítem dentro de la lista (sólo se usa el desfase, el tope es
 *   [MAX_STEPS] para que las listas largas no acumulen espera).
 * @param distance cuánto sube el ítem al entrar; 26 dp se nota sin marear.
 * @param contentKey identidad del ítem: si cambia, la aparición se repite (por ejemplo al
 *   reordenar la lista).
 */
@Composable
internal fun RevealItem(
    index: Int,
    modifier: Modifier = Modifier,
    distance: Dp = 26.dp,
    contentKey: Any? = Unit,
    content: @Composable () -> Unit,
) {
    val motion = LocalUiSettings.current.animationSpeed
    val animated = motion.scale > 0f && distance > 0.dp

    var entered by remember(contentKey) { mutableStateOf(!animated) }

    LaunchedEffect(contentKey) {
        if (!animated) return@LaunchedEffect
        val steps = index.coerceIn(0, MAX_STEPS)
        val wait = (steps * STEP_MS * motion.scale).toLong()
        if (wait > 0L) delay(wait)
        entered = true
    }

    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = Motion.enter(Motion.HOP_MS),
        label = "reveal",
    )

    androidx.compose.foundation.layout.Box(
        modifier = modifier.graphicsLayer {
            if (progress >= 1f) {
                alpha = 1f
                translationY = 0f
                scaleX = 1f
                scaleY = 1f
            } else {
                // Curva de entrada suave: el ítem arranca un poco más chico y abajo, y
                // "se asienta" al llegar. El desenfoque no se usa acá a propósito (sería
                // una pasada de RenderEffect por fila, el peor costo posible en una lista).
                alpha = progress
                translationY = (1f - progress) * distance.toPx()
                val s = 0.97f + 0.03f * progress
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            }
        },
    ) {
        content()
    }
}

/**
 * Igual que [RevealItem] pero para contenido suelto que entra de una vez (no una lista):
 * encabezados, tarjetas de una sola pieza, bloques de resultado.
 */
@Composable
internal fun RevealBlock(
    modifier: Modifier = Modifier,
    distance: Dp = 18.dp,
    delayMs: Int = 0,
    content: @Composable () -> Unit,
) {
    RevealItem(
        index = if (delayMs <= 0) 0 else (delayMs / STEP_MS).coerceAtLeast(0),
        modifier = modifier,
        distance = distance,
        content = content,
    )
}
