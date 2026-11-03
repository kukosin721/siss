package com.kukosin.app.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Curvas y duraciones de las animaciones de la app.
 *
 * # Por qué centralizarlas
 *
 * Con cada pantalla definiendo su propio `tween(220)` o `spring(stiffness = …)`, la app
 * terminaba con una docena de movimientos distintos que se sentían "pegados": un cambio de
 * pantalla entraba con una curva, la carátula con otra y el deslizador con una tercera.
 * Acá hay tres curvas con un criterio:
 *
 *  - [Emphasized]: para lo que **cambia de contenido** (carátula, título, pantalla). Entra
 *    rápido y desacelera al final, que es lo que el ojo lee como "peso".
 *  - [Standard]: para lo que **aparece o desaparece** (hojas, barras, avisos).
 *  - [SpringSnappy]: para lo que responde **al dedo** (deslizador, chips, botones). Un
 *    resorte con amortiguación alta: sin rebote visible, pero sin el corte seco del tween.
 *
 * Todas las duraciones se escalan solas: la escala de animación elegida en Ajustes entra al
 * `Recomposer` (ver `MainActivity`), así que "Off", "Slow" o "Fast" afectan a toda la app sin
 * cambiar una línea acá.
 */
object Motion {

    /** Contenido: entra con decisión y frena suave. */
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Apariciones: más corta y previsible. */
    val standard: Easing = FastOutSlowInEasing

    /** Salidas: acelera hacia afuera (el ojo ya dejó de mirar). */
    val exit: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    const val ENTER_MS = 320
    const val EXIT_MS = 220
    const val QUICK_MS = 160

    /** Transición de carátula: un poco más larga que el resto, se ve con calma. */
    const val ARTWORK_MS = 420

    /**
     * Aparición de un ítem de lista o tarjeta.
     *
     * Más corta que [ENTER_MS] a propósito: cuando entran 12 elementos escalonados, cada
     * uno tiene que terminar antes de que el siguiente empiece a llamar la atención; si
     * durara 320 ms la ola se pisa y se siente lenta.
     */
    const val HOP_MS = 260

    /** Recorrido de una transición entre pantallas (biblioteca → categoría → detalle). */
    const val SCREEN_MS = 300

    fun <T> enter(durationMillis: Int = ENTER_MS) =
        tween<T>(durationMillis = durationMillis, easing = emphasized)

    fun <T> leave(durationMillis: Int = EXIT_MS) =
        tween<T>(durationMillis = durationMillis, easing = exit)

    fun <T> quick(durationMillis: Int = QUICK_MS) =
        tween<T>(durationMillis = durationMillis, easing = standard)

    /** Resorte de respuesta al dedo: rápido, sin rebote perceptible. */
    fun <T> snappy(
        stiffness: Float = Spring.StiffnessMediumLow,
        dampingRatio: Float = 0.9f,
    ) = spring<T>(stiffness = stiffness, dampingRatio = dampingRatio)

    /** Resorte para cambios de tamaño/posición que deben sentirse físicos. */
    fun <T> gentle() = spring<T>(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow)
}

/**
 * Blur de las transiciones, según lo que el usuario eligió en Ajustes.
 *
 * El blur no es gratis: en Android cada `Modifier.blur` con un radio no trivial implica una
 * pasada de `RenderEffect` sobre la capa, y encadenar varias (carátula + fondo + hoja) en
 * una animación es la forma más fácil de bajar de 60 fps en un equipo de gama media. Por eso
 * el radio se expone como ajuste: **Off** deja las transiciones sin blur (las mismas
 * animaciones, sin la pasada de efecto), **Soft** es el valor por defecto y **Strong** es
 * para quien tiene GPU de sobra.
 */
enum class BlurStrength(val label: String, val scale: Float, val description: String) {
    OFF("Off", 0f, "No blur. The lightest option and the smoothest on older devices."),
    SOFT("Soft", 1f, "Subtle blur while the player sheet moves and on artwork transitions. Recommended."),
    STRONG("Strong", 1.7f, "More blur for a stronger effect. Needs a fast GPU to stay smooth."),
}

/**
 * Radios de blur ya escalados. Se leen con [LocalBlurRadius] dentro de un `@Composable`.
 *
 * Los valores base salen de las animaciones que ya existían (transición de carátula y
 * arrastre); multiplicarlos por el ajuste mantiene el mismo lenguaje visual sin duplicar
 * constantes por pantalla.
 */
data class BlurRadii(
    /** Blur del fondo cuando la carátula entra o sale (antes: 0–18 dp fijo). */
    val artworkTransition: Dp,
    /** Blur de la carátula mientras se arrastra (antes: hasta 8 dp). */
    val artworkDrag: Dp,
    /** Blur de las capas que aparecen encima del contenido (hojas, paneles). */
    val overlay: Dp,
    /**
     * Blur de **navegación**: lo que se ve detrás mientras la sección cambia (biblioteca →
     * categoría, categoría → detalle). Es el desenfoque "sutil y de movimiento" que pide
     * la navegación: no se queda fijo, sólo existe durante el cruce.
     */
    val navigate: Dp,
) {
    val enabled: Boolean get() = artworkTransition > 0.dp
}

/** Radios por defecto (los que se usan cuando no hay ajuste cargado todavía). */
val DefaultBlurRadii = BlurRadii(
    artworkTransition = 18.dp,
    artworkDrag = 6.dp,
    overlay = 10.dp,
    navigate = 7.dp,
)

/** Escala un juego de radios por la fuerza elegida. */
fun BlurStrength.radii(): BlurRadii {
    if (scale == 0f) return BlurRadii(0.dp, 0.dp, 0.dp, 0.dp)
    return BlurRadii(
        artworkTransition = (18f * scale).dp,
        artworkDrag = (6f * scale).dp,
        overlay = (10f * scale).dp,
        navigate = (7f * scale).dp,
    )
}

/**
 * Devuelve true si el sistema pide "reducir movimiento". Se usa para bajar las animaciones
 * grandes (carátula, fondo) sin apagar las transiciones cortas, que ayudan a entender qué
 * pasó aunque no haya movimiento.
 */
@Composable
@ReadOnlyComposable
fun animationsEnabled(animationScale: AnimationSpeed): Boolean =
    animationScale != AnimationSpeed.OFF
