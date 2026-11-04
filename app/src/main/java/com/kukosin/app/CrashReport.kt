package com.kukosin.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kukosin.app.ui.theme.Cream
import com.kukosin.app.ui.theme.CreamMuted
import com.kukosin.app.ui.theme.Gold
import com.kukosin.app.ui.theme.Ink
import com.kukosin.app.ui.theme.InkSoft
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Registro de fallos de arranque.
 *
 * El problema que resuelve: si la app se cierra al abrir, desde el teléfono no hay forma
 * de ver el log (sin PC no hay `adb logcat`). Acá el último fallo se guarda en disco y la
 * **próxima** apertura muestra el texto del error en pantalla, así se puede leer o sacar
 * una captura.
 *
 * Se guardan dos cosas:
 *  - la excepción (handler global de hilos, con el stacktrace completo), y
 *  - una marca de "arrancó" que se borra cuando la UI ya se dibujó. Si la app muere antes
 *    de esa marca y no hay excepción guardada, el fallo fue nativo (SIGSEGV en el motor de
 *    audio, por ejemplo), que es justo el caso que el handler de Java no ve.
 */
object CrashReport {

    private const val PREFS = "kukosin_crash_reports"
    private const val KEY_EXCEPTION = "last_exception"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_RUNS_SINCE = "runs_since_ready"
    private const val MAX_CHARS = 24_000

    private var installed = false

    /** Se llama lo antes posible en `Application.onCreate`. */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { saveException(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Marca que el proceso arrancó; [markReady] la borra cuando la UI ya se dibujó. */
    fun markStart(context: Context) {
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putLong(KEY_STARTED_AT, System.currentTimeMillis())
                .putInt(KEY_RUNS_SINCE, prefs.getInt(KEY_RUNS_SINCE, 0) + 1)
                .commit()
        }
    }

    fun markReady(context: Context) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_STARTED_AT)
                .putInt(KEY_RUNS_SINCE, 0)
                .commit()
        }
    }

    /** El texto a mostrar la próxima vez que se abra la app, o `null` si no hay nada. */
    fun takeReport(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_EXCEPTION, null)
        val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
        val runs = prefs.getInt(KEY_RUNS_SINCE, 0)
        return when {
            saved != null -> saved
            // Arrancó, no llegó a dibujar y volvió a arrancar sin excepción guardada: el
            // proceso murió por debajo de Java. Dos corridas para no marear con un corte
            // puntual (el usuario cerrando desde recientes, por ejemplo).
            startedAt > 0L && runs >= 2 -> buildString {
                appendLine("La app se cerró antes de terminar de abrir.")
                appendLine("No hay excepción de Java: el proceso murió por debajo (nativo).")
                appendLine()
                append(deviceInfo(context))
            }
            else -> null
        }
    }

    fun clear(context: Context) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    private fun saveException(context: Context, thread: Thread, error: Throwable) {
        val stack = StringWriter()
        error.printStackTrace(PrintWriter(stack))
        val text = buildString {
            appendLine("Fallo en el hilo \"${thread.name}\"")
            appendLine(error.javaClass.name + ": " + (error.message ?: ""))
            appendLine()
            append(deviceInfo(context))
            appendLine()
            append(stack.toString())
        }.take(MAX_CHARS)
        // `commit` y no `apply`: el proceso está por morir y un write diferido se pierde.
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_EXCEPTION, text)
            .commit()
    }

    private fun deviceInfo(context: Context): String = buildString {
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            appendLine("App: ${info.versionName} (${info.versionCode})")
        }
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        appendLine("Build: " + if (debuggable) "debug (sin R8)" else "release (R8)")
    }

    /** Pantalla que muestra el último fallo. Se dibuja antes que el reproductor. */
    @Composable
    fun CrashReportScreen(
        report: String,
        onContinue: () -> Unit,
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    "La app se cerró la última vez",
                    color = Gold,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Copiá o sacá una captura de este texto para poder arreglarlo.",
                    color = CreamMuted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = InkSoft,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                        ) {
                            Text(
                                report,
                                color = Cream,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onContinue,
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Reintentar", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
