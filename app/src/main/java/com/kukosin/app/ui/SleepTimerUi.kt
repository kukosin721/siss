package com.kukosin.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kukosin.app.playback.sleepTimerLabel
import com.kukosin.app.ui.theme.Cream
import com.kukosin.app.ui.theme.CreamMuted
import com.kukosin.app.ui.theme.Gold
import com.kukosin.app.ui.theme.InkElevated
import com.kukosin.app.ui.theme.InkSoft

/** Minutos que se ofrecen en el temporizador. */
private val SLEEP_OPTIONS = listOf(5, 10, 15, 30, 45, 60, 90)

/**
 * Selector del temporizador de sueño.
 *
 * Si ya está andando, arriba se ve cuánto falta y un botón para apagarlo; abajo, las
 * duraciones. Tocar una duración **reinicia** la cuenta (es lo que uno espera cuando ya
 * hay uno andando y toca otra).
 */
@Composable
internal fun SleepTimerDialog(
    remainingMs: Long,
    onSelectMinutes: (Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = InkElevated,
        titleContentColor = Cream,
        textContentColor = CreamMuted,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = Gold, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sleep timer", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (remainingMs > 0L) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Gold.copy(alpha = 0.12f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Playing for ${sleepTimerLabel(remainingMs)} more",
                                color = Gold,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "It fades out and pauses by itself",
                                color = CreamMuted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Rounded.Close, contentDescription = "Turn off", tint = CreamMuted)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                SLEEP_OPTIONS.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    ) {
                        for (minutes in row) {
                            SleepOption(
                                minutes = minutes,
                                modifier = Modifier.weight(1f),
                                onClick = { onSelectMinutes(minutes) },
                            )
                            if (minutes != row.last()) Spacer(Modifier.width(8.dp))
                        }
                        // Completa la fila si quedan huecos (7 opciones → 3 + 3 + 1).
                        repeat(3 - row.size) {
                            Spacer(Modifier.width(8.dp))
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Gold, fontWeight = FontWeight.Bold)
            }
        },
    )
}

@Composable
private fun SleepOption(
    minutes: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(InkSoft)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "$minutes",
            color = Cream,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text("min", color = CreamMuted, style = MaterialTheme.typography.labelSmall)
    }
}
