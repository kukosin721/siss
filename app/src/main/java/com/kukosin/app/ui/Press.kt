package com.kukosin.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Lo que hace un ítem cuando lo tocás.
 *
 * En toda la app faltaba esto: el dedo toca una tarjeta, un botón de la barra inferior o
 * una fila, y lo único que pasa es el ripple. El resultado es que la interfaz se siente
 * "de vidrio": nada responde hasta que la pantalla cambia. Un hundimiento de 3 % con un
 * desenfoque exactamente cero (es una matriz en la capa, no una pasada de efecto) le dice
 * al dedo que el toque llegó antes de que la acción se complete.
 *
 * # Por qué no usa `animateFloatAsState` por ítem en listas largas
 *
 * Sí lo usa, pero sólo cuando el ítem tiene un `InteractionSource` que recibe toques: la
 * animación de un ítem que nadie está tocando está en reposo (target 1f, sin frames). El
 * costo real es cuando tocás, y ahí es una sola capa animándose.
 */

/** Escala objetivo mientras el dedo está apoyado. */
private const val PRESSED_SCALE = 0.965f

@Composable
internal fun rememberPressScale(
    interaction: InteractionSource,
    scale: Float = PRESSED_SCALE,
): Float {
    val pressed by interaction.collectIsPressedAsState()
    // Un solo tick al empezar a presionar, no en cada cambio: el ojo ya ve el hundimiento,
    // el dedo sólo necesita confirmación.
    LaunchedEffect(pressed) {
        if (pressed) hapticTick()
    }
    val value by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        // Rápido al presionar (el dedo ya está ahí) y apenas más lento al soltar: si
        // vuelve con la misma curva se siente gomoso.
        animationSpec = if (pressed) Motion.snappy(stiffness = 900f, dampingRatio = 0.9f) else Motion.snappy(stiffness = 500f, dampingRatio = 0.85f),
        label = "pressScale",
    )
    return value
}

/**
 * Aplica el hundimiento sobre el `Modifier` del elemento. Se combina con `.clickable(...)`
 * del mismo `InteractionSource`.
 */
@Composable
internal fun Modifier.pressScale(
    interaction: InteractionSource,
    scale: Float = PRESSED_SCALE,
): Modifier {
    val value = rememberPressScale(interaction, scale)
    return this.graphicsLayer {
        scaleX = value
        scaleY = value
    }
}

/**
 * Fundido del contenido propio de la fila cuando se está tocando. Ojo: es un `alpha` de
 * capa, no un cambio de color, así no invalida el dibujo de los textos.
 */
@Composable
internal fun Modifier.pressAlpha(
    interaction: InteractionSource,
    from: Float = 1f,
    to: Float = 0.86f,
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val value by animateFloatAsState(
        targetValue = if (pressed) to else from,
        animationSpec = Motion.quick(120),
        label = "pressAlpha",
    )
    return this.graphicsLayer { alpha = value }
}

/**
 * Puente de háptica.
 *
 * `MainActivity` publica acá cómo pedir un tick al `Vibrator` del sistema; los composables
 * llaman a [hapticTick] sin saber nada de Android. Si nadie lo publicó (por ejemplo en un
 * preview), la llamada no hace nada. Así la animación y el toque no dependen de la versión
 * de `LocalHapticFeedback` que traiga Compose.
 */
internal object HapticsBridge {
    var tick: () -> Unit = {}
}

/** Tick corto para acompañar un toque que navega. Silencioso si el equipo no vibra. */
internal fun hapticTick() {
    runCatching { HapticsBridge.tick() }
}
