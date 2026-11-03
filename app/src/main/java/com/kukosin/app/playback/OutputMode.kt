package com.kukosin.app.playback

/**
 * Audio output path. Five routes with distinct semantics, not five interchangeable
 * "drivers":
 *
 * | Mode              | Path                          | Volume                    | Bit-perfect   |
 * |-------------------|-------------------------------|---------------------------|---------------|
 * | AUTO              | Oboe, best available device   | system / DAC attenuator   | with a DAC    |
 * | USB_DAC           | Oboe exclusive, track format  | system / DAC attenuator   | when no atten.|
 * | DIRECT_DRIVER     | Oboe exclusive + hw volume    | system / DAC attenuator   | with DAC      |
 * | AAUDIO_EXCLUSIVE  | Oboe exclusive + digital vol. | in the app (v³ curve)     | only at 100 % |
 * | DISABLED          | Android mixer (AudioTrack)    | free                      | never         |
 *
 * Ningún modo deja el volumen "fijo al 100 %": si el DAC no expone su atenuador, el
 * volumen se aplica en el motor (y se informa que la ruta deja de ser bit-exacta).
 *
 * [DISABLED] is the current default: Oboe is temporarily off while its audio problems
 * are fixed, so playback goes through the Android mixer (AudioTrack). [AUTO] and the
 * three exclusive modes remain selectable in Settings for testing; AUTO opens the
 * output through the Oboe engine at the track's native sample rate and bit depth and
 * falls back to a shared stream only when the hardware cannot take the exact format.
 */
enum class OutputMode(
    val label: String,
    val shortLabel: String,
    val description: String,
) {
    AUTO(
        label = "Automatic · Oboe",
        shortLabel = "Oboe",
        description = "Always plays through the Oboe engine on the best output available " +
            "(USB DAC, line out, headphones or speaker). It opens each track at its native " +
            "sample rate and bit depth and only converts when the hardware cannot take it. " +
            "Highest fidelity, but Oboe is not the default right now: it is being fixed.",
    ),
    USB_DAC(
        label = "USB DAC",
        shortLabel = "USB DAC",
        description = "Opens the USB DAC at each track's sample rate and bit depth, " +
            "without mixing or resampling. Volume is fixed: adjust it on the DAC.",
    ),
    DIRECT_DRIVER(
        label = "Built-in DAC · direct driver",
        shortLabel = "Direct",
        description = "The app opens the headphone/line output exclusively and volume " +
            "is adjusted in hardware, so the signal is not digitally attenuated. " +
            "If a USB DAC is connected, it requests permission to take it over.",
    ),
    AAUDIO_EXCLUSIVE(
        label = "Built-in DAC · AAudio exclusive",
        shortLabel = "AAudio excl.",
        description = "Opens the built-in output (jack or line) exclusively at each " +
            "track's sample rate. Volume is digital: bit-perfect only at 100 %.",
    ),
    DISABLED(
        label = "Disabled · Android mixer",
        shortLabel = "Mixer",
        description = "Default for now: goes through the Android mixer (AudioTrack) instead " +
            "of Oboe, which is temporarily off while its audio problems are fixed. Maximum " +
            "compatibility, but the system may resample or apply effects. Not bit-perfect.",
    ),
    ;

    /** ¿Usa el motor de Oboe? (sólo DISABLED va por AudioTrack / mezclador del sistema). */
    val usesEngine: Boolean get() = this != DISABLED

    /** Modos que exigen stream exclusivo: si el HAL no lo da, se informa el error. */
    val requiresExclusive: Boolean
        get() = this == USB_DAC || this == DIRECT_DRIVER || this == AAUDIO_EXCLUSIVE

    /** Sólo se puede elegir si hay un DAC conectado. AUTO y DISABLED siempre están disponibles. */
    val requiresDac: Boolean get() = requiresExclusive

    /**
     * Ya no hay modos con el volumen "bloqueado": un volumen que no se puede mover es
     * un fallo de la app, no una decisión de fidelidad. Se conserva la propiedad para
     * la UI (siempre `false`).
     */
    val locksVolume: Boolean get() = false

    /** El volumen se aplica en la app (atenuación digital). */
    val usesDigitalVolume: Boolean get() = this == AAUDIO_EXCLUSIVE

    /** El volumen se aplica sobre el dispositivo (hardware), no sobre la muestra. */
    val usesHardwareVolume: Boolean
        get() = this == DIRECT_DRIVER || this == USB_DAC || this == AUTO

    /** A qué dispositivo apunta el modo. */
    val targetsUsb: Boolean get() = this == USB_DAC || this == DIRECT_DRIVER

    val needsUsbPermission: Boolean get() = this == DIRECT_DRIVER

    /** Sin DAC USB este modo no tiene sentido (no hay a dónde abrir el stream). */
    val requiresUsb: Boolean get() = this == USB_DAC

    val volumeHint: String
        get() = when {
            usesDigitalVolume -> "Digital volume in the app (bit-perfect only at 100 %)"
            usesHardwareVolume -> "System DAC volume · the samples are never attenuated"
            else -> "System volume"
        }

    companion object {
        /**
         * Orden de la UI: primero lo que funciona siempre, después lo exclusivo y al
         * final la salida de emergencia.
         */
        val entriesInOrder: List<OutputMode> =
            listOf(AUTO, USB_DAC, DIRECT_DRIVER, AAUDIO_EXCLUSIVE, DISABLED)

        /** Un nombre desconocido (o una preferencia vacía) cae en el modo seguro: sin Oboe. */
        fun fromName(name: String?): OutputMode =
            entries.firstOrNull { it.name == name } ?: DISABLED
    }
}
