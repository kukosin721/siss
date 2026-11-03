@file:Suppress("unused", "ClassName")

package android.hardware.usb

/** Stubs de tipado (no se incluyen en el APK): sólo lo que usa `UsbDacManager`. */
class UsbDevice : android.os.Parcelable {
    val deviceClass: Int = 0
    val vendorId: Int = 0
    val productId: Int = 0
    val productName: String? = null
    val deviceName: String = ""
    val interfaceCount: Int = 0
    val configurationCount: Int = 0
    fun getInterface(index: Int): UsbInterface = UsbInterface()
    fun getConfiguration(index: Int): UsbConfiguration = UsbConfiguration()
}

class UsbInterface {
    val interfaceClass: Int = 0
    val interfaceSubclass: Int = 0
    val interfaceProtocol: Int = 0
    val endpointCount: Int = 0
    fun getEndpoint(index: Int): UsbEndpoint = UsbEndpoint()
}

class UsbConfiguration {
    val interfaceCount: Int = 0
    fun getInterface(index: Int): UsbInterface = UsbInterface()
}

class UsbEndpoint {
    val type: Int = 0
    val direction: Int = 0
    val maxPacketSize: Int = 0
    val interval: Int = 0
}

class UsbDeviceConnection {
    fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray,
        length: Int,
        timeout: Int,
    ): Int = 0

    val rawDescriptors: ByteArray? = null
    fun claimInterface(iface: UsbInterface, force: Boolean): Boolean = false
    fun releaseInterface(iface: UsbInterface): Boolean = false
    fun close() = Unit
}

class UsbManager {
    val deviceList: Map<String, UsbDevice> = emptyMap()
    fun hasPermission(device: UsbDevice): Boolean = false
    fun openDevice(device: UsbDevice): UsbDeviceConnection? = null
    fun requestPermission(device: UsbDevice, pi: android.app.PendingIntent) = Unit

    companion object {
        const val EXTRA_DEVICE = "device"
        const val EXTRA_PERMISSION_GRANTED = "permission"
        const val ACTION_USB_DEVICE_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
        const val ACTION_USB_DEVICE_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED"
    }
}

object UsbConstants {
    const val USB_CLASS_AUDIO = 1
}
