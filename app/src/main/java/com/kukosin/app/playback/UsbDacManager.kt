package com.kukosin.app.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UsbDacInfo(
    val hasUsbDevice: Boolean = false,
    val deviceName: String = "",
    val vendorId: Int = 0,
    val productId: Int = 0,
    val hasPermission: Boolean = false,
    val isDirectHardwareAccess: Boolean = false,
    val usbDevice: UsbDevice? = null,
    /** El DAC expone un atenuador por USB Audio Class (volumen sin tocar las muestras). */
    val hardwareVolume: Boolean = false,
    /** Nivel actual del atenuador del DAC, en dB (null si no hay control). */
    val hardwareVolumeDb: Float? = null,
)

object UsbDacManager {
    private const val ACTION_USB_PERMISSION = "com.kukosin.app.USB_PERMISSION"

    /** Timeout de los control requests: son de 2 bytes y el DAC responde al instante. */
    private const val CONTROL_TIMEOUT_MS = 200

    private val statusFlow = MutableStateFlow(UsbDacInfo())
    val current: StateFlow<UsbDacInfo> = statusFlow.asStateFlow()

    private var receiverRegistered = false
    private var permissionRequested = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null) {
                        scanDevices(context)
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED == action || UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                scanDevices(context)
            }
        }
    }

    fun init(context: Context) {
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(usbReceiver, filter)
            }
            receiverRegistered = true
        }
        scanDevices(context)
    }

    fun requestPermission(context: Context) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val device = findAudioDevice(usbManager) ?: return
        if (usbManager.hasPermission(device)) {
            permissionRequested = false
            scanDevices(context)
            return
        }
        if (permissionRequested) return
        permissionRequested = true

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION),
            flags,
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    fun scanDevices(context: Context) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            permissionRequested = false
            closeHardwareVolume()
            statusFlow.value = UsbDacInfo()
            return
        }

        val device = findAudioDevice(usbManager)
        if (device == null) {
            permissionRequested = false
            // DAC desconectado: el atenuador que quede abierto apunta a un dispositivo que
            // ya no existe, y su `controlTransfer` fallaría en cada cambio de volumen.
            closeHardwareVolume()
            statusFlow.value = UsbDacInfo()
            return
        }

        val name = device.productName?.trim().orEmpty().ifBlank { device.deviceName }
        val hasPerm = usbManager.hasPermission(device)

        statusFlow.value = UsbDacInfo(
            hasUsbDevice = true,
            deviceName = name,
            vendorId = device.vendorId,
            productId = device.productId,
            hasPermission = hasPerm,
            isDirectHardwareAccess = hasPerm,
            usbDevice = device,
        )
    }

    // --------------------------------------------------------------- volumen del DAC
    //
    // Bajar el volumen sin tocar las muestras: se usa el atenuador del propio DAC (Feature
    // Unit de USB Audio Class). En modo exclusivo/bit-perfect es la única forma de tener
    // volumen sin romper la fidelidad, porque el volumen de Android no llega al DAC y el
    // digital re-cuantiza. Ver `DacHardwareVolume`.
    private var volumeControl: DacHardwareVolume.Control? = null
    private var volumeConnection: UsbDeviceConnection? = null
    private var volumeUac2 = false

    /** ¿Se puede mover el volumen del DAC? (hay control y el DAC lo confirmó). */
    fun hasHardwareVolume(): Boolean = volumeControl?.usable == true

    /**
     * Abre el control de volumen del DAC conectado. Devuelve true si el DAC expone una
     * Feature Unit usable. Silencioso a propósito: si el DAC no tiene atenuador, la app
     * sigue funcionando con el volumen normal.
     */
    fun openHardwareVolume(context: Context): Boolean {
        closeHardwareVolume()
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val device = findAudioDevice(usbManager) ?: return false
        if (!usbManager.hasPermission(device)) return false

        val connection = runCatching { usbManager.openDevice(device) }.getOrNull() ?: return false
        val descriptors = runCatching { connection.rawDescriptors }.getOrNull()
        if (descriptors == null) {
            runCatching { connection.close() }
            return false
        }
        val control = DacHardwareVolume.findFeatureUnit(descriptors)
        if (control == null) {
            runCatching { connection.close() }
            return false
        }
        val uac2 = control.uac2
        val transfer = { type: Int, request: Int, value: Int, index: Int, data: ByteArray ->
            connection.controlTransfer(type, request, value, index, data, data.size, CONTROL_TIMEOUT_MS)
        }
        val ranged = DacHardwareVolume.readRange(control, uac2, transfer)
        if (ranged == null || !ranged.usable) {
            runCatching { connection.close() }
            return false
        }
        volumeControl = ranged
        volumeConnection = connection
        volumeUac2 = uac2
        publishVolumeDb(ranged.currentDb)
        return true
    }

    fun closeHardwareVolume() {
        val control = volumeControl
        val connection = volumeConnection
        if (control != null && connection != null) {
            // Se deja el DAC como estaba: el atenuador vive en el hardware y si no se
            // restaura, queda bajado para Android y para las demás apps.
            runCatching {
                DacHardwareVolume.restore(control) { type, request, value, index, data ->
                    connection.controlTransfer(
                        type, request, value, index, data, data.size, CONTROL_TIMEOUT_MS,
                    )
                }
            }
        }
        runCatching { connection?.close() }
        volumeControl = null
        volumeConnection = null
    }

    /**
     * Pone el volumen del DAC en [fraction] (0..1). Devuelve `false` si no hay control o
     * el DAC no confirmó el valor (en ese caso no se debe mostrar como aplicado).
     */
    fun setHardwareVolume(fraction: Float): Boolean {
        val control = volumeControl ?: return false
        val connection = volumeConnection ?: return false
        val db = DacHardwareVolume.dbOf(fraction, control)
        val applied = DacHardwareVolume.setDb(control, { type, request, value, index, data ->
            connection.controlTransfer(type, request, value, index, data, data.size, CONTROL_TIMEOUT_MS)
        }, db) ?: return false
        publishVolumeDb(applied)
        return true
    }

    /** Un paso de volumen (+1 / -1) dentro del recorrido útil. */
    fun stepHardwareVolume(direction: Int): Boolean {
        val control = volumeControl ?: return false
        val current = control.currentDb ?: return false
        val fraction = DacHardwareVolume.fractionOf(current, control)
        val step = 1f / DacHardwareVolume.STEPS
        return setHardwareVolume((fraction + direction * step).coerceIn(0f, 1f))
    }

    private fun publishVolumeDb(db: Float?) {
        val current = statusFlow.value
        if (current.hardwareVolumeDb == db && current.hardwareVolume == (db != null)) return
        statusFlow.value = current.copy(hardwareVolume = db != null, hardwareVolumeDb = db)
    }

    private fun findAudioDevice(usbManager: UsbManager): UsbDevice? {
        val devices = usbManager.deviceList.values
        return devices.firstOrNull { isAudioUsbDevice(it) }
    }

    private fun isAudioUsbDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_AUDIO) return true
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO) return true
        }
        return false
    }
}
