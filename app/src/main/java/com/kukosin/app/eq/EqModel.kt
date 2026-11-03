package com.kukosin.app.eq

import kotlin.math.abs

/**
 * Tipo de filtro de una banda.
 *
 * LOW_SHELF / HIGH_SHELF: shelves de primer orden en las puntas (no colorean el resto
 * del espectro). PEAK: campana constante-Q en el resto de las bandas.
 */
enum class EqBandType {
    LOW_SHELF,
    PEAK,
    HIGH_SHELF,

    /** Corte estrecho: mata un zumbido (50/60 Hz) sin tocar el resto. */
    NOTCH,

    /** Pasa-bajos: deja pasar todo lo que está por debajo de la frecuencia. */
    LOW_PASS,

    /** Pasa-altos: deja pasar todo lo que está por encima de la frecuencia. */
    HIGH_PASS,
    ;

    /** Sólo estas bandas responden a un cambio de ganancia (dB). */
    val usesGain: Boolean get() = this == LOW_SHELF || this == PEAK || this == HIGH_SHELF

    val label: String
        get() = when (this) {
            LOW_SHELF -> "Low shelf"
            PEAK -> "Peak"
            HIGH_SHELF -> "High shelf"
            NOTCH -> "Notch"
            LOW_PASS -> "Low pass"
            HIGH_PASS -> "High pass"
        }
}

/**
 * Una banda del ecualizador.
 *
 * @param freqHz frecuencia central (o de corte, en los shelves).
 * @param gainDb ganancia en dB, rango util -12..+12.
 * @param q factor Q (ancho de banda). Solo aplica a PEAK; los shelves usan S fijo.
 */
data class EqBand(
    val freqHz: Float,
    val gainDb: Float,
    val q: Float = 1.41f,
    val type: EqBandType = EqBandType.PEAK,
) {
    /** `true` si la banda aporta ganancia (las de corte no). */
    val isGainBand: Boolean get() = type.usesGain

    /** `true` si la banda está haciendo algo (corte activo o ganancia distinta de 0). */
    val isActive: Boolean
        get() = if (isGainBand) abs(gainDb) >= 0.05f else true
}

/**
 * Estado completo del ecualizador. Inmutable: cada cambio produce una copia nueva
 * que se publica al motor de audio sin locks (volatile + referencia).
 */
data class EqSettings(
    val enabled: Boolean = false,
    /** Ganancia manual de entrada, -12..+12 dB. */
    val preampDb: Float = 0f,
    /** Si está activo, resta automáticamente el máximo refuerzo para no clipear. */
    val autoHeadroom: Boolean = true,
    /** Solo informativo para la UI (nombre del preset aplicado o "Custom"). */
    val presetName: String = "Flat",
    val bands: List<EqBand> = DefaultEqBands,
) {
    /** Máximo refuerzo aplicado por una banda (>= 0). */
    val maxBoostDb: Float
        get() {
            var m = 0f
            for (b in bands) if (b.isGainBand && b.gainDb > m) m = b.gainDb
            return m
        }

    /**
     * Pico real de la curva de las bandas (sin preamp), calculado con los mismos
     * biquads que suenan y cacheado: la UI lo pide en cada muestra de la curva.
     */
    val curvePeakDb: Float by lazy {
        if (bands.none { it.isGainBand && abs(it.gainDb) >= 0.05f }) 0f
        else BiquadDesign.bandsPeakDb(bands, REFERENCE_SAMPLE_RATE).toFloat()
    }

    /** Headroom que se resta automáticamente (0 si está desactivado). */
    val autoHeadroomDb: Float get() = if (autoHeadroom) curvePeakDb.coerceAtLeast(0f) else 0f

    /** Preamp realmente aplicado por la cadena: manual - headroom automático. */
    val effectivePreampDb: Float get() = preampDb - autoHeadroomDb

    /** ¿Hay algún filtro de corte (notch / pasa-bajos / pasa-altos) en la cadena? */
    val hasCutFilters: Boolean get() = bands.any { !it.isGainBand }

    /** ¿Está todo plano? (se puede saltar la cadena por completo). */
    val isFlat: Boolean
        get() = !hasCutFilters &&
            abs(effectivePreampDb) < 0.05f &&
            bands.all { abs(it.gainDb) < 0.05f }

    /** ¿Hay algo que procesar? (si no, bypass real: no se toca ni un bit). */
    val isProcessing: Boolean get() = enabled && !isFlat

    fun withBandGain(index: Int, gainDb: Float): EqSettings =
        copy(
            bands = bands.mapIndexed { i, b ->
                if (i == index) b.copy(gainDb = gainDb.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)) else b
            },
            presetName = CUSTOM_PRESET,
        )

    fun withBandFrequency(index: Int, freqHz: Float): EqSettings =
        copy(
            bands = bands.mapIndexed { i, b ->
                if (i == index) b.copy(freqHz = freqHz.coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ)) else b
            },
            presetName = CUSTOM_PRESET,
        )

    fun withBandType(index: Int, type: EqBandType): EqSettings =
        copy(
            bands = bands.mapIndexed { i, b ->
                if (i == index) {
                    // Cada tipo tiene su Q natural: shelf suave, campana de octava y
                    // cortes estrechos (notch) o de pendiente Butterworth (LP/HP).
                    b.copy(
                        type = type,
                        q = when (type) {
                            EqBandType.PEAK -> 1.41f
                            EqBandType.NOTCH -> 8f
                            EqBandType.LOW_PASS, EqBandType.HIGH_PASS -> 0.71f
                            else -> 0.71f
                        },
                    )
                } else {
                    b
                }
            },
            presetName = CUSTOM_PRESET,
        )

    fun withBandQ(index: Int, q: Float): EqSettings =
        copy(
            bands = bands.mapIndexed { i, b ->
                if (i == index) b.copy(q = q.coerceIn(MIN_Q, MAX_Q)) else b
            },
            presetName = CUSTOM_PRESET,
        )

    companion object {
        const val MAX_GAIN_DB = 12f
        const val MIN_FREQ_HZ = 20f
        const val MAX_FREQ_HZ = 20000f
        const val MIN_Q = 0.30f
        const val MAX_Q = 6f
        const val CUSTOM_PRESET = "Custom"
        /** Rate de referencia para dibujar la curva y calcular el headroom. */
        const val REFERENCE_SAMPLE_RATE = 48_000.0

        /** Frecuencias ISO de octava: 31 Hz .. 16 kHz. */
        val DEFAULT_FREQUENCIES = floatArrayOf(
            31f, 63f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f,
        )

    }
}

/** Banda por defecto: shelf en los extremos + campanas de octava al centro. */
val DefaultEqBands: List<EqBand> = EqSettings.DEFAULT_FREQUENCIES.mapIndexed { i, f ->
    val type = when (i) {
        0 -> EqBandType.LOW_SHELF
        EqSettings.DEFAULT_FREQUENCIES.lastIndex -> EqBandType.HIGH_SHELF
        else -> EqBandType.PEAK
    }
    EqBand(
        freqHz = f,
        gainDb = 0f,
        q = if (type == EqBandType.PEAK) 1.41f else 0.71f,
        type = type,
    )
}

/** Presets: ganancias en dB para las 10 bandas por defecto. */
object EqPresets {

    data class Preset(val name: String, val gainsDb: List<Float>)

    val all: List<Preset> = listOf(
        Preset("Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Preset("Warm", listOf(3f, 2.5f, 1.5f, 0.5f, 0f, -0.5f, -1f, -1f, -0.5f, 0f)),
        Preset("Crystal", listOf(-1f, -0.5f, 0f, 0f, 0.5f, 1f, 1.5f, 2f, 2.5f, 2f)),
        Preset("Vocal", listOf(-2f, -1f, 0f, 1f, 2.5f, 3f, 2f, 1f, 0f, -1f)),
        Preset("Rock", listOf(4f, 3f, 1.5f, -0.5f, -1.5f, -0.5f, 1f, 2.5f, 3f, 3.5f)),
        Preset("Pop", listOf(-1f, 0.5f, 2f, 3f, 1.5f, 0f, -1f, -0.5f, 1.5f, 2f)),
        Preset("Jazz", listOf(2.5f, 1.5f, 0.5f, 1f, -0.5f, -1f, 0f, 1f, 1.5f, 2f)),
        Preset("Classic", listOf(2f, 1f, 0f, 0f, 0f, 0f, -0.5f, -1f, -1.5f, -2f)),
        Preset("Bass", listOf(6f, 5f, 3.5f, 1f, 0f, -0.5f, -1f, -1f, -0.5f, 0f)),
        Preset("V signature", listOf(5f, 4f, 2f, 0f, -1.5f, -2f, -1f, 1.5f, 3.5f, 4.5f)),
    )

    val default: Preset get() = all.first()

    fun byName(name: String): Preset? = all.firstOrNull { it.name == name }
}

/** Aplica un preset sobre el estado actual conservando frecuencias/Q. */
fun EqSettings.applyingPreset(preset: EqPresets.Preset): EqSettings = copy(
    bands = bands.mapIndexed { i, band ->
        if (band.isGainBand) {
            val g = preset.gainsDb.getOrElse(i) { 0f }
            band.copy(gainDb = g.coerceIn(-EqSettings.MAX_GAIN_DB, EqSettings.MAX_GAIN_DB))
        } else {
            band   // un notch o un pasa-altos no forma parte de un preset de color
        }
    },
    presetName = preset.name,
)

/**
 * Perfil del ecualizador según el tipo de salida.
 *
 * Un auricular, el altavoz del teléfono y un DAC USB no necesitan la misma curva: cada
 * uno guarda su propio estado (encendido, preamp, bandas y preset) y la app cambia de
 * perfil sola cuando cambia la salida.
 */
enum class EqProfile(val label: String, val hint: String) {
    BUILTIN("Speaker", "The built-in speaker never sounds like headphones"),
    WIRED("Headphones / line", "Jack or USB-C without its own DAC"),
    USB("USB DAC", "The external DAC has its own response"),
    ;

    companion object {
        /** Clasificación simple y honesta a partir de lo que reporta Android. */
        fun forDevice(isBuiltin: Boolean, isUsbOrDac: Boolean): EqProfile = when {
            isUsbOrDac -> USB
            isBuiltin -> BUILTIN
            else -> WIRED
        }
    }
}

/** Utilidades de formato para la UI. */
fun formatFrequency(hz: Float): String = when {
    hz >= 10_000f -> "${roundTo(hz / 1_000f, 1)}k"
    hz >= 1_000f -> "${roundTo(hz / 1_000f, 1)}k"
    else -> "${hz.toInt()}"
}

fun formatGain(db: Float): String {
    val v = roundTo(db, 1)
    val sign = if (v > 0.05f) "+" else ""
    return "$sign${v} dB"
}

private fun roundTo(value: Float, decimals: Int): Float {
    var factor = 1f
    repeat(decimals) { factor *= 10f }
    return Math.round(value * factor).toFloat() / factor
}
