package com.kukosin.app.playback

/**
 * Arnés del volumen de hardware del DAC, sin Android ni dispositivo.
 *
 * Comprueba el parseo de descriptores USB Audio Class con descriptores **sintéticos**
 * armados byte a byte (UAC1 y UAC2), el redondeo al paso del DAC, el recorrido útil del
 * control y la confirmación por read-back.
 *
 * Ejecutar:  kotlinc DacHardwareVolume.kt DacVolumeProbe.kt -include-runtime -d probe.jar
 *            java -jar probe.jar
 */
private var checks = 0
private var failures = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) failures++
    println((if (condition) "OK    " else "FALLA ") + name)
}

private fun checkNear(name: String, actual: Float, expected: Float, tolerance: Float) {
    val ok = kotlin.math.abs(actual - expected) <= tolerance
    checks++
    if (!ok) failures++
    println((if (ok) "OK    " else "FALLA ") + "$name: $actual (esperado $expected ± $tolerance)")
}

// ---------------------------------------------------------------- descriptores

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

/** Interfaz de audio + Feature Unit (maestro controlable) + terminal de salida. */
private fun descriptor(
    uac2: Boolean,
    interfaceNumber: Int = 1,
    featureUnitFirst: Boolean = false,
): ByteArray {
    // Ojo: bLength = 9 significa 9 bytes. Un fixture más largo desalinea TODO el parseo
    // (esto fue un bug real en la primera versión de estas pruebas).
    val interfaceDescriptor = bytes(
        9, 0x04, interfaceNumber, 0x00, 0x00, 0x00, 0x00,
        if (uac2) 0x20 else 0x00, 0x00,
    )
    val head = bytes(
        9, 0x04, interfaceNumber, 0x01, 0x01, 0x01, 0x00,
        if (uac2) 0x20 else 0x00, 0x00,
    )
    val outputTerminal = if (uac2) {
        // bLength 12, OUTPUT_TERMINAL, id 4, wTerminalType 0x0301 (speaker), bSourceID 3
        // UAC2: idx7 = bSourceID (3), idx8 = bCSourceID, idx9-10 = bmControls
        bytes(12, 0x24, 0x03, 0x04, 0x01, 0x03, 0x03, 0x03, 0x00, 0x00, 0x00, 0x00)
    } else {
        // UAC1: idx6 = bAssocTerminal, idx7 = iTerminal, idx8 = bSourceID (3)
        bytes(9, 0x24, 0x03, 0x04, 0x01, 0x03, 0x00, 0x00, 0x03)
    }
    val featureUnit = if (uac2) {
        // bLength 11: UNIT(3) + FU id(3) + source(3) + bmaControls[0] = 3 (maestro RW)
        bytes(11, 0x24, 0x06, 0x03, 0x02, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00)
    } else {
        // bLength 9: UNIT(3) + FU id(3) + source(3) + bmaControls[0] = 2 (maestro con volumen)
        bytes(9, 0x24, 0x06, 0x03, 0x02, 0x02, 0x00, 0x00, 0x00)
    }
    val body = if (featureUnitFirst) {
        interfaceDescriptor + head + featureUnit + outputTerminal
    } else {
        interfaceDescriptor + head + outputTerminal + featureUnit
    }
    val total = body.size + 9
    val config = bytes(
        9, 0x02, (total and 0xFF), ((total shr 8) and 0xFF), 0x01, 0x01, 0x00, 0x80, 0x32,
    )
    return config + body
}

/** Descriptor sin Feature Unit: no debe inventarse un control. */
private fun descriptorWithoutFeatureUnit(): ByteArray {
    val head = bytes(9, 0x04, 1, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00)
    val outputTerminal = bytes(9, 0x24, 0x03, 0x04, 0x01, 0x03, 0x00, 0x00, 0x03)
    val body = head + outputTerminal
    val total = body.size + 9
    return bytes(9, 0x02, (total and 0xFF), ((total shr 8) and 0xFF), 1, 1, 0, 0x80, 0x32) + body
}

fun main() {
    println("Volumen de hardware del DAC (USB Audio Class), descriptores sintéticos")
    println("=============================================================")

    // 1. UAC1: encuentra la Feature Unit de la salida.
    val uac1 = DacHardwareVolume.findFeatureUnit(descriptor(uac2 = false))
    check("UAC1: encuentra Feature Unit", uac1 != null)
    check("UAC1: unidad e interfaz correctas", uac1?.unitId == 3 && uac1?.interfaceNumber == 1)

    // 2. UAC2 idem (cambia el tamaño de bmaControls).
    val uac2 = DacHardwareVolume.findFeatureUnit(descriptor(uac2 = true))
    check("UAC2: encuentra Feature Unit", uac2 != null && uac2.unitId == 3)

    // 3. Sin Feature Unit no se inventa nada (nunca tocar un control de captura).
    check(
        "sin Feature Unit -> null (no adivina)",
        DacHardwareVolume.findFeatureUnit(descriptorWithoutFeatureUnit()) == null,
    )

    // 4. Descriptor corrupto (longitud imposible) -> null.
    check("descriptor corrupto -> null", DacHardwareVolume.findFeatureUnit(bytes(0, 9, 0x24)) == null)

    // 5. Rango: UAC1 (min/max/res por separado).
    val base = uac1!!
    val uac1Range = DacHardwareVolume.readRange(base, uac2 = false) { _, request, _, _, data ->
        when (request) {
            0x81 -> { data[0] = 0x00.toByte(); data[1] = 0x80.toByte() }            // actual: -128 dB
            0x82 -> { data[0] = 0x00.toByte(); data[1] = 0x80.toByte() }            // mínimo: -128 dB
            0x83 -> { data[0] = 0x00; data[1] = 0x00 }            // máximo: 0 dB
            0x84 -> { data[0] = 0x00.toByte(); data[1] = 0x01.toByte() }   // paso: 256 raw = 1 dB
            else -> return@readRange -1
        }
        2
    }
    check("UAC1: rango leído", uac1Range != null)
    checkNear("UAC1: mínimo en dB", uac1Range?.minDb ?: 0f, -128f, 0.01f)
    checkNear("UAC1: máximo acotado a 0 dB", uac1Range?.maxDb ?: 1f, 0f, 0.01f)
    checkNear("UAC1: paso en dB", uac1Range?.stepDb ?: 0f, 1f, 0.01f)
    checkNear("UAC1: nivel actual recordado", uac1Range?.currentDb ?: 0f, -128f, 0.01f)

    // 6. Rango UAC2 (RANGE con encabezado de cantidad).
    val uac2Range = DacHardwareVolume.readRange(uac2!!, uac2 = true) { _, request, _, _, data ->
        when (request) {
            0x81 -> { data[0] = 0x00.toByte(); data[1] = 0xCE.toByte() }            // actual: -50 dB
            0x02 -> {
                if (data.size >= 8) {
                    data[0] = 0x01.toByte(); data[1] = 0x00.toByte()   // count = 1
                    data[2] = 0x00.toByte(); data[3] = 0x81.toByte()                 // -127 dB
                    data[4] = 0x00.toByte(); data[5] = 0x00.toByte()                 // 0 dB
                    data[6] = 0x20.toByte(); data[7] = 0x00.toByte()                 // paso 0.125 dB
                } else {
                    data[0] = 0x01.toByte(); data[1] = 0x00.toByte()
                }
            }
            else -> return@readRange -1
        }
        0
    }
    check("UAC2: rango leído", uac2Range != null)
    checkNear("UAC2: mínimo", uac2Range?.minDb ?: 0f, -127f, 0.01f)
    checkNear("UAC2: paso de 0.125 dB", uac2Range?.stepDb ?: 0f, 0.125f, 0.001f)

    // 7. Cuantización al paso del DAC, siempre hacia abajo.
    val control = uac1Range!!
    // -6.5 dB no es múltiplo del paso de 1 dB: siempre se redondea HACIA ABAJO.
    check("cuantiza -6.5 dB hacia abajo (-7 dB)", DacHardwareVolume.quantize(-6.5f, control) == -7 * 256)
    check("cuantiza -6.0 dB exacto (-6 dB)", DacHardwareVolume.quantize(-6.0f, control) == -6 * 256)
    check("cuantiza -6.99 dB hacia abajo (-7 dB)", DacHardwareVolume.quantize(-6.99f, control) == -7 * 256)
    check("en el mínimo devuelve el mínimo", DacHardwareVolume.quantize(-500f, control) == -128 * 256)

    // 8. Recorrido útil: el fondo del control es el mínimo del DAC, y -80 dB el resto.
    checkNear("fracción en el máximo", DacHardwareVolume.fractionOf(0f, control), 1f, 0.001f)
    checkNear("fracción en el piso útil", DacHardwareVolume.fractionOf(-80f, control), 0f, 0.001f)
    check("fracción en el mínimo absoluto = 0", DacHardwareVolume.fractionOf(-128f, control) == 0f)
    checkNear("dB de la fracción 1.0", DacHardwareVolume.dbOf(1f, control), 0f, 0.001f)
    checkNear("dB de la fracción 0.5", DacHardwareVolume.dbOf(0.5f, control), -40f, 0.01f)

    // 9. setDb confirma con read-back; si el DAC no confirma, se informa null.
    var written = -1
    val applied = DacHardwareVolume.setDb(control, { _, request, _, _, data ->
        if (request == 0x01) {
            written = (data[0].toInt() and 0xFF) or ((data[1].toInt() shl 8))
            2
        } else {
            data[0] = (written and 0xFF).toByte()
            data[1] = ((written shr 8) and 0xFF).toByte()
            2
        }
    }, -12f)
    checkNear("setDb aplica -12 dB", applied ?: 0f, -12f, 0.01f)
    val rejected = DacHardwareVolume.setDb(control, { _, request, _, _, data ->
        if (request == 0x01) 2 else { data[0] = 0x00.toByte(); data[1] = 0x00.toByte(); 2 }
    }, -12f)
    check("read-back que no coincide -> null", rejected == null)

    // 10. Restaurar deja el DAC como estaba.
    var restored = -1
    DacHardwareVolume.restore(control) { _, request, _, _, data ->
        if (request == 0x01) {
            restored = (data[0].toInt() and 0xFF) or ((data[1].toInt() shl 8))
            2
        } else {
            -1
        }
    }
    check("restore devuelve el nivel original", restored == -128 * 256)

    println("=============================================================")
    println(if (failures == 0) "$checks comprobaciones, 0 fallas"
    else "$checks comprobaciones, $failures FALLAS")
    if (failures != 0) kotlin.system.exitProcess(1)
}
