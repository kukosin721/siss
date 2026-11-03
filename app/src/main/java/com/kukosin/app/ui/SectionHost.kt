package com.kukosin.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Navegación animada entre las tres secciones de primer nivel.
 *
 * # Qué se mueve y qué no
 *
 * Antes el `when (activeSection)` cambiaba de pantalla de golpe: el contenido aparecía
 * completo, ya compuesto, y la única pista de que hubo un cambio era que el texto era
 * otro. Acá las secciones se **desplazan**: la que entra viene desde el lado hacia el que
 * estás navegando y la que sale se va al lado contrario, con un fundido corto encima para
 * que el cruce no se vea como dos capas peleando.
 *
 * La dirección sale del orden declarado en [AppSection] ([directionOf]), así que
 * Biblioteca → Ecualizador → Búsqueda siempre se mueve hacia la izquierda y volver se
 * mueve hacia la derecha, sin tener que mantener un historial.
 *
 * # Por qué el desplazamiento es corto
 *
 * 42 dp y no el ancho completo: una pantalla completa que entra desde el borde se siente
 * como un "empujón" y cuesta leerla mientras se mueve. Con un recorrido corto el ojo
 * sigue el contenido sin perder el hilo, que es exactamente lo que hace que la navegación
 * se sienta fluida en lugar de aparatosa.
 */
@Composable
internal fun SectionHost(
    section: AppSection,
    modifier: Modifier = Modifier,
    content: @Composable (AppSection) -> Unit,
) {
    AnimatedContent(
        targetState = section,
        modifier = modifier,
        transitionSpec = { sectionTransform(initialState, targetState) },
        label = "sectionHost",
    ) { target ->
        content(target)
    }
}

/** Dirección del desplazamiento: +1 si se navega "hacia adelante", -1 si se vuelve. */
internal fun directionOf(from: AppSection, to: AppSection): Int {
    val order = AppSection.entries
    val a = order.indexOf(from)
    val b = order.indexOf(to)
    return when {
        a == b -> 0
        b > a -> 1
        else -> -1
    }
}

private fun sectionTransform(from: AppSection, to: AppSection): ContentTransform {
    val dir = directionOf(from, to)
    if (dir == 0) return fadeIn(tween(1)) togetherWith fadeOut(tween(1))
    val travel = { full: Int -> (full / 12).coerceAtLeast(28) }
    val enter = slideInHorizontally(
        animationSpec = Motion.enter(Motion.SCREEN_MS),
        initialOffsetX = { full -> dir * travel(full) },
    ) + fadeIn(tween(Motion.SCREEN_MS / 2))
    val exit = slideOutHorizontally(
        animationSpec = Motion.leave(Motion.SCREEN_MS),
        targetOffsetX = { full -> -dir * travel(full) },
    ) + fadeOut(tween(Motion.SCREEN_MS))
    // Sin `SizeTransform`: las tres secciones ocupan la pantalla completa, así que animar
    // el tamaño sería trabajo de layout gratis en cada frame. `ContentTransform` con
    // `sizeTransform = null` (el valor por defecto) ya significa exactamente eso, y además
    // el setter es `internal` en Compose.
    return enter togetherWith exit
}
