package com.kukosin.app

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import com.kukosin.app.ui.PlayerApp
import com.kukosin.app.ui.HapticsBridge
import com.kukosin.app.ui.UiSettingsManager
import com.kukosin.app.ui.theme.AppTheme
import com.kukosin.app.ui.theme.Ink

class MainActivity : ComponentActivity() {
    @OptIn(InternalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // La escala de animación elegida en Ajustes entra al contexto del Recomposer:
        // así afecta a todas las animaciones de Compose, no sólo a las nuevas.
        //
        // Nota: el Recomposer se queda en el hilo principal a propósito. Moverlo a
        // `Dispatchers.Default` acelera la composición, pero los `Dialog` y `Popup` de
        // Compose crean ventanas de Android durante la composición, y eso exige un hilo
        // con Looper. La fluidez se busca donde sí se puede medir: menos trabajo por
        // recomposición (ver `Motion.kt`), blur opcional y transiciones más cortas.
        WindowRecomposerPolicy.setFactory { rootView ->
            rootView.createLifecycleAwareWindowRecomposer(UiSettingsManager.motionDurationScale)
        }
        enableEdgeToEdge()
        installHaptics()
        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
                    PlayerApp()
                }
            }
        }
    }
}

/**
 * Publica un "tick" de háptica para la interfaz.
 *
 * La navegación animada sin retorno táctil se siente de vidrio: el contenido se desliza
 * pero el dedo no recibió nada. Un tick corto y de amplitud baja por cada toque que navega
 * (abrir una categoría, entrar a una carpeta, cambiar de sección) es la mitad de lo que se
 * percibe como "respuesta" — y no cuesta ni un frame, porque no toca el hilo de UI.
 *
 * Va acá y no con `LocalHapticFeedback` de Compose porque ese camino no existe en todas las
 * versiones y en varios equipos ignora el ajuste de intensidad del sistema. Este respeta la
 * configuración del usuario: si desactivó las hápticas, `Vibrator` no hace nada.
 */
private fun ComponentActivity.installHaptics() {
    HapticsBridge.tick = {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            when {
                vibrator == null || !vibrator.hasVibrator() -> Unit
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                    vibrator.vibrate(VibrationEffect.createOneShot(8L, 60))
                else -> {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(8L)
                }
            }
        }
    }
}
