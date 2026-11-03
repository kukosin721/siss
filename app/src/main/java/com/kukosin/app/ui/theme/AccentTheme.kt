package com.kukosin.app.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * Presets de color de acento.
 *
 * Todos usan la misma saturación y luminosidad que el dorado original (#E8B86D); solo cambia
 * el tono. Así el contraste con el fondo oscuro y los textos crema se mantiene igual en todos.
 */
enum class AccentPreset(val label: String, val hueDegrees: Float) {
    ORO("Gold", 37f),
    AMBAR("Amber", 25f),
    COBRE("Copper", 14f),
    ROSA("Pink", 340f),
    MAGENTA("Magenta", 315f),
    VIOLETA("Violet", 270f),
    AZUL("Blue", 212f),
    CIAN("Cyan", 188f),
    ESMERALDA("Emerald", 155f),
    LIMA("Lime", 95f),
}

/**
 * Estado global del color de acento.
 *
 * Es estado de Compose ([mutableFloatStateOf]), así que cualquier composición que lea
 * [Gold] vuelve a pintarse sola cuando cambia el tono. Se guarda en SharedPreferences para
 * que sobreviva al cierre de la app.
 */
object AccentTheme {

    private const val PREFS_NAME = "kukosin_appearance"
    private const val KEY_HUE = "accent_hue"

    /** Tono del dorado original: 37°. Es el valor por defecto. */
    const val DEFAULT_HUE = 37f

    private var prefs: SharedPreferences? = null

    /** Tono actual, en grados (0..360). */
    var hue by mutableFloatStateOf(DEFAULT_HUE)
        private set

    val color: Color
        get() = accentColor(hue)

    val colorDim: Color
        get() = accentDimColor(hue)

    fun init(context: Context) {
        if (prefs != null) return
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = preferences
        hue = preferences.getFloat(KEY_HUE, DEFAULT_HUE).let(::normalize)
    }

    /** Vista previa en vivo (slider): cambia el tono sin escribir en disco en cada frame. */
    fun preview(newHue: Float) {
        hue = normalize(newHue)
    }

    /** Guarda el tono actual. */
    fun commit() {
        prefs?.edit()?.putFloat(KEY_HUE, hue)?.apply()
    }

    /** Elige un preset y lo guarda. */
    fun select(preset: AccentPreset) {
        preview(preset.hueDegrees)
        commit()
    }

    fun isSelected(preset: AccentPreset): Boolean = abs(hue - preset.hueDegrees) < 0.5f

    /** Volver al dorado de fábrica. */
    fun reset() {
        preview(DEFAULT_HUE)
        commit()
    }

    private fun normalize(value: Float): Float {
        val wrapped = value % 360f
        return if (wrapped < 0f) wrapped + 360f else wrapped
    }
}

/** Color de acento para un tono dado, con la luminosidad/saturación del dorado original. */
fun accentColor(hueDegrees: Float): Color = hsl(hueDegrees, saturation = 0.73f, lightness = 0.67f)

/** Variante oscura del acento (la que antes era `GoldDim`). */
fun accentDimColor(hueDegrees: Float): Color = hsl(hueDegrees, saturation = 0.52f, lightness = 0.48f)

private fun hsl(hueDegrees: Float, saturation: Float, lightness: Float): Color {
    val chroma = (1f - abs(2f * lightness - 1f)) * saturation
    val sector = (hueDegrees % 360f) / 60f
    val second = chroma * (1f - abs(sector % 2f - 1f))

    val (red, green, blue) = when (sector.toInt()) {
        0 -> Triple(chroma, second, 0f)
        1 -> Triple(second, chroma, 0f)
        2 -> Triple(0f, chroma, second)
        3 -> Triple(0f, second, chroma)
        4 -> Triple(second, 0f, chroma)
        else -> Triple(chroma, 0f, second)
    }

    val match = lightness - chroma / 2f
    return Color(red + match, green + match, blue + match)
}
