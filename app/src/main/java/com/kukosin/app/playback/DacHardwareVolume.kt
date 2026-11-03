package com.kukosin.app.playback

/**
 * Volumen del DAC por USB Audio Class (Feature Unit).
 *
 * # Por qué existe
 *
 * En el camino bit-perfect el volumen **no puede** aplicarse a las muestras: cualquier
 * multiplicación las cambia y deja de ser bit-perfect. Hasta ahora la app bajaba el
 * volumen de la pista en el motor (escala en float) o usaba el volumen de Android, que en
 * modo exclusivo no llega al DAC. La única forma de bajar el volumen sin tocar un solo
 * bit es el atenuador del propio DAC: la *Feature Unit* del descriptor USB Audio Class,
 * que se maneja con un control request por el pipe 0 (`UsbDeviceConnection
 * .controlTransfer`).
 *
 * Esto es exactamente lo que hacen Mornye (`usb_audio/volume.rs` + `UsbVolume.kt`) y
 * BitChord (`UsbVolume.kt`, `DacVolume.kt`). Esta implementación es una reescritura
 * propia, sin dependencias (el proyecto no incluye libusb), sobre las APIs públicas de
 * Android.
 *
 * # Qué hace y qué no
 *
 * - Sigue **sólo** cadenas de terminal de reproducción sin ambigüedad: si el descriptor
 *   no describe una única Feature Unit en el camino hacia el terminal de salida, no se
 *   toca nada (devolver `null` es preferible a escribirle a un control de captura).
 * - Trabaja en pasos enteros de 1/256 dB, que es la unidad de UAC1 y UAC2.
 * - Lee el rango real del DAC y **redondea hacia abajo** al paso del dispositivo.
 * - Verifica con *read-back*: si el DAC no confirmó el valor pedido, se informa el error
 *   en vez de suponer que se aplicó.
 * - Al soltar, restaura el nivel que el DAC tenía antes, para no dejarle el atenuador
 *   bajado a las demás apps.
 *
 * Limitación honesta: `UsbManager` en Android no permite *claim*-ear la interfaz de audio
 * mientras el driver del sistema la tiene tomada. Por eso esto se usa cuando la salida es
 * la del DAC por AAudio (exclusivo/MMAP): el atenuador es independiente del stream y
 * funciona igual. El audio directo por USB (isócrono, sin AAudio) requeriría libusb, que
 * es un cambio de arquitectura aparte.
 */
internal object DacHardwareVolume {

    /** Clase de request USB estándar/class-specific que necesitamos. */
    private const val TYPE_CLASS_INTERFACE_OUT = 0x21

    // Códigos de la Audio Class (UAC1 y UAC2 comparten los de volumen).
    private const val REQ_SET_CUR = 0x01
    private const val REQ_GET_CUR = 0x81
    private const val REQ_GET_MIN = 0x82
    private const val REQ_GET_MAX = 0x83
    private const val REQ_GET_RES = 0x84
    private const val REQ_GET_RANGE_UAC2 = 0x02 // RANGE en UAC2 (con CUR = 1)

    /** Canal maestro de la Feature Unit: selector 0x00, con la unidad en el índice. */
    private const val FU_MASTER = 0x00

    /** Topes de seguridad: un descriptor que diga otra cosa está corrupto. */
    private const val MAX_CHANNELS = 8

    /**
     * Un control de volumen ya validado: la unidad, la interfaz dueña, la cantidad de
     * canales controlables y el rango que declara el DAC.
     */
    data class Control(
        val unitId: Int,
        val interfaceNumber: Int,
        val channels: Int,
        val minDb: Float,
        val maxDb: Float,
        val stepDb: Float,
        /** La interfaz del control habla UAC2 (bInterfaceProtocol 0x20). */
        val uac2: Boolean = false,
    ) {
        /** Nivel actual leído del DAC, en dB. */
        var currentDb: Float? = null
            private set

        /** Nivel que tenía el DAC antes de que la app lo tocara (para restaurar). */
        var originalRaw: Int? = null
            private set

        internal fun remember(originalRaw: Int, currentDb: Float) {
            this.originalRaw = originalRaw
            this.currentDb = currentDb
        }

        internal fun updated(db: Float) {
            currentDb = db
        }

        /** `true` si el DAC declara un rango útil (mínimo < máximo). */
        val usable: Boolean get() = maxDb > minDb
    }

    /**
     * Interpreta los descriptores de configuración en busca de la Feature Unit de
     * reproducción. Devuelve `null` si hay ambigüedad; nunca adivina.
     *
     * Equivalente a `UsbVolume.feature()` de BitChord, sin copiar su estructura: acá sólo
     * se aceptan descriptores de la interfaz de streaming que llega al terminal de
     * salida, y se exige que todos los canales sean controlables (o el maestro).
     */
    fun findFeatureUnit(configDescriptor: ByteArray): Control? {
        var offset = 0
        var activeInterface = -1
        var activeAlternate = -1
        var uac2 = false
        // id de entidad -> fuente de la que viene
        val sources = HashMap<Int, Int>()
        val features = HashMap<Int, Int>()   // id -> canales controlables
        val featureInterface = HashMap<Int, Int>()
        val featureUac2 = HashMap<Int, Boolean>()
        val outputs = mutableListOf<Int>()
        var outputTerminal = -1

        fun byte(index: Int): Int = configDescriptor[index].toInt() and 0xFF

        while (offset + 2 <= configDescriptor.size) {
            val length = byte(offset)
            if (length < 2 || offset + length > configDescriptor.size) return null
            val type = byte(offset + 1) and 0xFF

            when (type) {
                0x04 -> {   // INTERFACE
                    if (length >= 9) {
                        activeInterface = byte(offset + 2)
                        activeAlternate = byte(offset + 3)
                        uac2 = byte(offset + 7) == 0x20   // bInterfaceProtocol 0x20 = UAC2
                    }
                }

                0x24 -> {   // descriptor de la Audio Class
                    if (length >= 4) {
                        when (byte(offset + 2)) {
                            0x03 -> {   // OUTPUT TERMINAL
                                // El índice de bSourceID depende de la versión: UAC1 lo
                                // pone en el byte 8 (después de iTerminal) y UAC2 en el 7
                                // (antes de bCSourceID). Equivocarlo desconecta la cadena
                                // y el volumen "no existe" aunque el DAC sí lo tenga.
                                val sourceIndex = offset + (if (uac2) 7 else 8)
                                if (length >= 9 && sourceIndex < offset + length) {
                                    val terminal = byte(offset + 3)
                                    sources[terminal] = byte(sourceIndex)
                                    outputTerminal = terminal
                                    outputs += terminal
                                }
                            }

                            0x06 -> {   // FEATURE UNIT
                                if (length >= 7 && activeInterface >= 0) {
                                    val unit = byte(offset + 3)
                                    sources[unit] = byte(offset + 4)
                                    // bmaControls: UAC1 empieza en 6 con tamaño 1,
                                    // UAC2 empieza en 5 con tamaño 4.
                                    val size = if (uac2) 4 else 1
                                    val start = if (uac2) 5 else 6
                                    var channels = 0
                                    val limit = ((length - start) / size).coerceAtMost(MAX_CHANNELS + 1)
                                    for (channel in 0 until limit) {
                                        val position = start + channel * size
                                        if (position + size > length) break
                                        val writable = if (uac2) {
                                            (byte(offset + position) shr 2) and 0x03 == 0x03
                                        } else {
                                            byte(offset + position) and 0x02 != 0
                                        }
                                        if (writable) channels = channel
                                    }
                                    features[unit] = channels
                                    featureInterface[unit] = activeInterface
                                    featureUac2[unit] = uac2
                                }
                            }
                        }
                    }
                }
            }
            offset += length
            // Sólo interesan los descriptores de la interfaz activa; el resto se ignora
            // (el `when` ya filtra por interfaz en la Feature Unit).
            if (activeAlternate > 1) activeInterface = -1
        }

        // Se recorre la cadena desde cada terminal de salida hasta encontrar una Feature
        // Unit; si hay más de una candidata, hay ambigüedad y no se toca nada.
        val candidates = mutableListOf<Int>()
        for (output in outputs) {
            var id = output
            val seen = mutableSetOf<Int>()
            while (id != -1 && seen.add(id)) {
                features[id]?.let { candidates += id }
                if (id == outputTerminal && candidates.isNotEmpty()) break
                id = sources[id] ?: -1
            }
        }
        val unit = candidates.distinct().singleOrNull() ?: return null
        return Control(
            unitId = unit,
            interfaceNumber = featureInterface[unit] ?: return null,
            channels = features[unit] ?: return null,
            uac2 = featureUac2[unit] ?: false,
            minDb = 0f,
            maxDb = 0f,
            stepDb = 0f,
        )
    }

    /**
     * Rango de volumen que declara el DAC, leído del propio dispositivo.
     *
     * @param transfer función que hace el control request (en producción,
     *   `UsbDeviceConnection::controlTransfer`); se inyecta para poder probar sin USB.
     */
    fun readRange(
        control: Control,
        uac2: Boolean,
        transfer: (requestType: Int, request: Int, value: Int, index: Int, data: ByteArray) -> Int,
    ): Control? {
        val index = (control.unitId shl 8) or control.interfaceNumber
        val value = (REQ_GET_CUR shl 8) or FU_MASTER
        val current = ByteArray(2)
        if (transfer(TYPE_CLASS_INTERFACE_OUT, REQ_GET_CUR, value, index, current) < 0) return null
        if (uac2) {
            val rangeHeader = ByteArray(2)
            if (transfer(TYPE_CLASS_INTERFACE_OUT, REQ_GET_RANGE_UAC2, value, index, rangeHeader) < 0) {
                return null
            }
            val count = (rangeHeader[0].toInt() and 0xFF) or ((rangeHeader[1].toInt() and 0xFF) shl 8)
            if (count !in 1..16) return null
            val all = ByteArray(2 + 6 * count)
            if (transfer(TYPE_CLASS_INTERFACE_OUT, REQ_GET_RANGE_UAC2, value, index, all) < 0) return null
            return build(control, current, all, 2)
        }
        // UAC1: mínimo, máximo y resolución se piden por separado y en orden.
        val all = ByteArray(6)
        for ((position, request) in listOf(REQ_GET_MIN, REQ_GET_MAX, REQ_GET_RES).withIndex()) {
            val part = ByteArray(2)
            if (transfer(TYPE_CLASS_INTERFACE_OUT, request, value, index, part) < 0) return null
            part.copyInto(all, position * 2)
        }
        return build(control, current, all, 0)
    }

    private fun build(control: Control, current: ByteArray, range: ByteArray, at: Int): Control? {
        val min = s16(range, at)
        val max = s16(range, at + 2)
        val step = s16(range, at + 4)
        if (min > max || step < 0) return null
        val result = control.copy(
            minDb = min / 256f,
            maxDb = minOf(max / 256f, 0f),   // nunca por encima de 0 dB (evita recortes)
            stepDb = step / 256f,
        )
        result.remember(originalRaw = s16(current, 0), currentDb = s16(current, 0) / 256f)
        return result
    }

    /**
     * Pone el DAC en [db] (acotado al rango y a 0 dB) y confirma con read-back.
     * Devuelve el nivel realmente aplicado, o `null` si el DAC no lo confirmó.
     */
    fun setDb(
        control: Control,
        transfer: (requestType: Int, request: Int, value: Int, index: Int, data: ByteArray) -> Int,
        db: Float,
    ): Float? {
        if (!db.isFinite()) return null
        val clamped = db.coerceIn(control.minDb, control.maxDb)
        val raw = quantize(clamped, control)
        val index = (control.unitId shl 8) or control.interfaceNumber
        val selector = 0x200 or FU_MASTER
        val payload = byteArrayOf((raw and 0xFF).toByte(), ((raw shr 8) and 0xFF).toByte())
        if (transfer(TYPE_CLASS_INTERFACE_OUT, REQ_SET_CUR, selector, index, payload) < 0) return null
        val readBack = ByteArray(2)
        if (transfer(TYPE_CLASS_INTERFACE_OUT, REQ_GET_CUR, selector, index, readBack) < 0) return null
        val actual = s16(readBack, 0)
        if (actual != raw) return null
        val applied = actual / 256f
        control.updated(applied)
        return applied
    }

    /** Devuelve el atenuador al nivel que tenía el DAC antes de que la app lo tocara. */
    fun restore(
        control: Control,
        transfer: (requestType: Int, request: Int, value: Int, index: Int, data: ByteArray) -> Int,
    ): Boolean {
        val original = control.originalRaw ?: return false
        val index = (control.unitId shl 8) or control.interfaceNumber
        val selector = 0x200 or FU_MASTER
        val payload = byteArrayOf((original and 0xFF).toByte(), ((original shr 8) and 0xFF).toByte())
        return transfer(TYPE_CLASS_INTERFACE_OUT, REQ_SET_CUR, selector, index, payload) >= 0
    }

    /**
     * Convierte dB a la unidad del DAC (1/256 dB) alineado al paso que declara.
     *
     * El redondeo es **hacia abajo** a propósito: redondear hacia arriba puede dejar el
     * volumen por encima de lo pedido (y en un atenuador analógico eso se oye).
     */
    internal fun quantize(db: Float, control: Control): Int {
        val raw = Math.floor(db * 256.0).toInt()
        val stepRaw = Math.max(1, Math.round(control.stepDb * 256f))
        val minRaw = Math.round(control.minDb * 256f)
        if (raw <= minRaw) return minRaw
        return minRaw + (raw - minRaw) / stepRaw * stepRaw
    }

    /**
     * Posición del control (0..1) correspondiente a un nivel en dB.
     *
     * Un DAC típico llega a −127 dB, donde el 60 % inferior del recorrido es inaudible;
     * por eso el recorrido útil empieza en [FLOOR_DB] y sólo su extremo inferior es el
     * mínimo del DAC (misma decisión que `DacVolume.fractionOf` de BitChord).
     */
    fun fractionOf(db: Float, control: Control): Float {
        val floor = maxOf(control.minDb, FLOOR_DB)
        if (control.maxDb <= floor || db <= floor) return 0f
        return ((db - floor) / (control.maxDb - floor)).coerceIn(0f, 1f)
    }

    /** Nivel en dB para una posición 0..1 del control. */
    fun dbOf(fraction: Float, control: Control): Float {
        val target = fraction.coerceIn(0f, 1f)
        if (target <= 0f) return control.minDb
        val floor = maxOf(control.minDb, FLOOR_DB)
        return floor + target * (control.maxDb - floor)
    }

    /** Piso del recorrido útil del control de volumen. */
    const val FLOOR_DB = -80f

    /** Pasos de los botones de volumen, como en iOS. */
    const val STEPS = 16

    private fun s16(bytes: ByteArray, at: Int): Int {
        if (at + 1 >= bytes.size) return 0
        return ((bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)).toShort().toInt()
    }
}
