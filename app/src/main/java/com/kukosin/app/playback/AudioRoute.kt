package com.kukosin.app.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OutputDevice(
    val name: String,
    val isDac: Boolean,
    val typeLabel: String,
    val isBitPerfectSupported: Boolean = false,
    val sampleRatesLabel: String = "",
    val hasUsbPermission: Boolean = false,
    val audioDeviceId: Int = 0,
    val isUsb: Boolean = false,
    val isBuiltin: Boolean = false,
)

@OptIn(UnstableApi::class)
object AudioRoute {
    private val output = MutableStateFlow(
        OutputDevice("System output", isDac = false, typeLabel = "Default"),
    )
    val current: StateFlow<OutputDevice> = output.asStateFlow()

    /**
     * `false` hasta que [attach] haya leído los dispositivos reales al menos una vez.
     * La UI espera esta señal antes de forzar el modo seguro: si arrancara con el
     * valor inicial (`isDac = false`) resetearía el modo de un DAC conectado antes
     * de que el servicio de reproducción lo detecte.
     */
    private val ready = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = ready.asStateFlow()

    @Volatile
    var preferredAudioDevice: AudioDeviceInfo? = null
        private set

    @Volatile
    var appContext: Context? = null
        private set

    /**
     * Un único callback para todo el proceso.
     *
     * `attach()` se llama en cada `recreatePlayer()` (o sea, cada vez que cambia un ajuste de
     * audio). Antes se registraba un callback nuevo en cada llamada y no se quitaba nunca:
     * se acumulaban y `applyRoute()` corría N veces por cada evento de dispositivo.
     */
    private var deviceCallback: AudioDeviceCallback? = null
    private var audioManager: AudioManager? = null
    private var attachedPlayer: Player? = null

    /**
     * Detecta la salida activa sin necesitar un reproductor. Se llama al arrancar la
     * app para que Ajustes sepa si hay un DAC antes de que exista un stream.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        UsbDacManager.init(context)
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        audioManager = manager
        applyRoute(context, requestPermission = false)
        registerDeviceCallback(context)
    }

    fun attach(context: Context, player: Player) {
        appContext = context.applicationContext
        UsbDacManager.init(context)
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        audioManager = manager
        attachedPlayer = player
        applyRoute(context, requestPermission = true)
        registerDeviceCallback(context)
    }

    private fun applyRoute(context: Context, requestPermission: Boolean) {
        val manager = audioManager ?: return
        val device = pickPreferred(manager)
        preferredAudioDevice = device
        val usbInfo = UsbDacManager.current.value
        if (requestPermission && usbInfo.hasUsbDevice && !usbInfo.hasPermission) {
            UsbDacManager.requestPermission(context)
        }
        output.value = describe(device, manager, usbInfo)
        ready.value = true
        val player = attachedPlayer
        if (player is ExoPlayer) {
            if (device != null && device.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && device.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                player.setPreferredAudioDevice(device)
            } else {
                player.setPreferredAudioDevice(null)
            }
        }
    }

    private fun registerDeviceCallback(context: Context) {
        if (deviceCallback != null) return
        val manager = audioManager ?: return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                UsbDacManager.scanDevices(context)
                applyRoute(context, requestPermission = true)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                UsbDacManager.scanDevices(context)
                applyRoute(context, requestPermission = true)
            }
        }
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        deviceCallback = callback
    }

    /** Deja de escuchar cambios de dispositivo (al cerrar el servicio). */
    fun detach() {
        attachedPlayer = null
        val callback = deviceCallback ?: return
        audioManager?.unregisterAudioDeviceCallback(callback)
        deviceCallback = null
        audioManager = null
    }

    private val USB_TYPES = setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
    )

    private val WIRED_TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
    )

    /**
     * Dispositivo al que apunta un modo de salida.
     *
     * Automático → la mejor salida disponible (USB, si no línea/jack; si no, la que
     * decida el sistema). DAC USB → sólo USB. Controlador directo → USB si está, si no
     * el codec con cable. AAudio exclusivo → jack / línea. Desactivado → el que decida
     * el sistema.
     */
    fun deviceFor(mode: OutputMode): AudioDeviceInfo? {
        val audioManager = audioManager ?: return null
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
        return when (mode) {
            OutputMode.AUTO -> devices.firstOrNull { it.type in USB_TYPES }
                ?: devices.firstOrNull { it.type in WIRED_TYPES }
            OutputMode.USB_DAC -> devices.firstOrNull { it.type in USB_TYPES }
            OutputMode.DIRECT_DRIVER -> devices.firstOrNull { it.type in USB_TYPES }
                ?: devices.firstOrNull { it.type in WIRED_TYPES }
            OutputMode.AAUDIO_EXCLUSIVE -> devices.firstOrNull { it.type in WIRED_TYPES }
            OutputMode.DISABLED -> null
        }
    }

    fun deviceIdFor(mode: OutputMode): Int = deviceFor(mode)?.id ?: 0

    private fun pickPreferred(audioManager: AudioManager): AudioDeviceInfo? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices
            .filter { it.isSink }
            .minByOrNull { priority(it.type) }
    }

    private fun priority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        -> 0
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        -> 1
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC,
        -> 2
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        -> 3
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        -> 4
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 8
        else -> 6
    }

    private fun describe(device: AudioDeviceInfo?, audioManager: AudioManager, usbInfo: UsbDacInfo): OutputDevice {
        if (device == null) {
            return OutputDevice("System output", isDac = false, typeLabel = "Default")
        }
        val isUsbDac = device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
            usbInfo.hasUsbDevice
        val isLineDac = device.type == AudioDeviceInfo.TYPE_LINE_ANALOG || device.type == AudioDeviceInfo.TYPE_LINE_DIGITAL
        val isDac = isUsbDac || isLineDac

        val typeLabel = when {
            isUsbDac -> "USB DAC (Host)"
            isLineDac -> "HiFi line output"
            device.type == AudioDeviceInfo.TYPE_HDMI || device.type == AudioDeviceInfo.TYPE_HDMI_ARC || device.type == AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI / Digital Audio"
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Analog output"
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || device.type == AudioDeviceInfo.TYPE_BLE_HEADSET || device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth Audio"
            device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
            else -> "Audio device"
        }

        val rates = device.sampleRates.filter { it > 0 }.distinct().sorted()
        val ratesText = if (rates.isNotEmpty()) {
            rates.map { if (it >= 1000) "${it / 1000}kHz" else "${it}Hz" }.joinToString(", ")
        } else {
            "Up to 384kHz+ (Direct USB)"
        }

        var bitPerfect = isDac
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val attrs = audioManager.getSupportedMixerAttributes(device)
            bitPerfect = attrs.any { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT } || isUsbDac
        }

        val product = usbInfo.deviceName.ifBlank { device.productName?.toString()?.trim().orEmpty() }
        val name = product.ifBlank { typeLabel }
        val isBuiltin = device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
            device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        return OutputDevice(
            name = name,
            isDac = isDac,
            typeLabel = typeLabel,
            isBitPerfectSupported = bitPerfect,
            sampleRatesLabel = ratesText,
            hasUsbPermission = usbInfo.hasPermission,
            audioDeviceId = device.id,
            isUsb = isUsbDac,
            isBuiltin = isBuiltin,
        )
    }
}
