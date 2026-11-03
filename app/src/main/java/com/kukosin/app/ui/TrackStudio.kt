package com.kukosin.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kukosin.app.data.analysis.AudioAnalysis
import com.kukosin.app.data.analysis.AuthenticityVerdict
import com.kukosin.app.ui.theme.Cream
import com.kukosin.app.ui.theme.CreamMuted
import com.kukosin.app.ui.theme.Gold
import com.kukosin.app.ui.theme.Ink
import com.kukosin.app.ui.theme.InkSoft
import kotlin.math.log10
import kotlin.math.roundToInt

@Composable
fun AnalysisPanel(
    analysis: AudioAnalysis?,
    modifier: Modifier = Modifier,
    onAnalyzeClick: (() -> Unit)? = null,
    scrollable: Boolean = true,
    /** false para streams de red (YouTube Music): no hay PCM local que analizar. */
    canAnalyze: Boolean = true,
) {
    if (analysis?.isAnalyzing == true) {
        Box(modifier = modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Gold)
                Spacer(Modifier.height(12.dp))
                Text("Decoding PCM and analyzing FFT spectrum…", color = CreamMuted)
            }
        }
        return
    }

    val hasSpectrum = analysis?.spectrumDb?.isNotEmpty() == true
    val verdictColor = when (analysis?.verdict) {
        AuthenticityVerdict.AUTHENTIC_HIRES, AuthenticityVerdict.AUTHENTIC_CD -> Gold
        AuthenticityVerdict.AUTHENTIC_LOSSY -> Color(0xFF64B5F6)
        AuthenticityVerdict.UPSAMPLED, AuthenticityVerdict.LOSSY_TRANSCODE -> Color(0xFFE07A5F)
        else -> CreamMuted
    }

    @Composable
    fun AnalysisContent() {
        Surface(
            color = InkSoft,
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = 0.4f)),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(if (hasSpectrum) verdictColor else Gold, RoundedCornerShape(5.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (hasSpectrum) analysis?.verdictTitle.orEmpty() else "Hi-Res Authenticity Check",
                            color = if (hasSpectrum) verdictColor else Gold,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        !canAnalyze -> "This audio is streamed from YouTube Music, so there are no local PCM samples to analyze. Spectral and authenticity analysis works with files on the device (FLAC, WAV, MP3, etc.)."
                        hasSpectrum -> analysis?.verdictDetail.orEmpty()
                        else -> "Analyze the PCM samples and FFT frequency spectrum to verify whether the file is 100% authentic, upsampled, or an MP3/AAC transcode."
                    },
                    color = Cream,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (canAnalyze) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { onAnalyzeClick?.invoke() },
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (hasSpectrum) "Re-analyze Spectrum and Authenticity" else "🔍 Verify Authenticity and Spectrum",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        if (hasSpectrum && analysis != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Frequency Spectrum (FFT 4096)", color = Gold, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Cutoff: ${analysis.cutoffHz} Hz",
                        color = Color(0xFFE07A5F),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                SpectrumChart(
                    magnitudes = analysis.spectrumDb,
                    frequencies = analysis.spectrumHz,
                    cutoffHz = analysis.cutoffHz.toFloat(),
                    nyquistHz = analysis.nyquistHz.toFloat(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Peak: ${"%.1f".format(analysis.peakDb)} dBFS", color = CreamMuted, style = MaterialTheme.typography.labelSmall)
                    Text("RMS: ${"%.1f".format(analysis.rmsDb)} dBFS", color = CreamMuted, style = MaterialTheme.typography.labelSmall)
                    Text("DR: ${"%.1f".format(analysis.dynamicRangeDb)} dB", color = Gold, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }

        Text("Technical Parameters and Extracted Metadata", color = Gold, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)

        val technicalList = analysis?.technical?.ifEmpty { analysis.tags }.orEmpty()
        technicalList.forEach { (label, value) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(InkSoft.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(label, color = CreamMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.42f))
                Text(value, color = Cream, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.58f))
            }
        }
    }

    if (scrollable) {
        LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { AnalysisContent() }
        }
    } else {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AnalysisContent()
        }
    }
}

@Composable
fun SpectrumChart(
    magnitudes: List<Float>,
    frequencies: List<Float>,
    cutoffHz: Float,
    nyquistHz: Float,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = CreamMuted.copy(alpha = 0.80f), fontSize = 9.sp)
    val axisStyle = TextStyle(color = CreamMuted.copy(alpha = 0.55f), fontSize = 8.sp)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .background(Ink, RoundedCornerShape(12.dp))
            .padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
    ) {
        if (magnitudes.isEmpty()) return@Canvas

        // Escala vertical real: -100 dBFS (silencio digital) a 0 dBFS.
        val minDb = -100f
        val maxDb = 0f
        // Eje logarítmico: así se ve un espectro de verdad, no una mancha a la
        // izquierda. De 20 Hz al Nyquist del archivo.
        val minHz = 20f
        val topHz = nyquistHz.coerceAtLeast(minHz * 4f)
        val logLo = log10(minHz)
        val logHi = log10(topHz)

        fun xForHz(hz: Float): Float {
            val clamped = hz.coerceIn(minHz, topHz)
            val t = (log10(clamped) - logLo) / (logHi - logLo)
            return (t * size.width).coerceIn(0f, size.width)
        }

        fun yForDb(db: Float): Float =
            ((db.coerceIn(minDb, maxDb) - minDb) / (maxDb - minDb)) * size.height

        // Región sin contenido por encima del corte: la firma visual del lossy.
        if (cutoffHz > 0f && cutoffHz < topHz) {
            val xCut = xForHz(cutoffHz)
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFFE07A5F).copy(alpha = 0.16f),
                        Color(0xFFE07A5F).copy(alpha = 0.03f),
                    ),
                    startX = xCut,
                    endX = size.width,
                ),
                topLeft = Offset(xCut, 0f),
                size = Size((size.width - xCut).coerceAtLeast(0f), size.height),
            )
        }

        // Rejilla horizontal cada 20 dB con etiqueta.
        var gridDb = -20f
        while (gridDb > minDb) {
            val y = size.height - yForDb(gridDb)
            drawLine(
                color = CreamMuted.copy(alpha = 0.09f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
            val text = textMeasurer.measure("${gridDb.toInt()}", axisStyle)
            drawText(text, topLeft = Offset(1f, (y - text.size.height).coerceAtLeast(0f)))
            gridDb -= 20f
        }

        // Rejilla vertical en las frecuencias que importan.
        val decades = listOf(20f, 50f, 100f, 200f, 500f, 1_000f, 2_000f, 5_000f, 10_000f, 20_000f)
        decades.filter { it <= topHz }.forEach { hz ->
            val x = xForHz(hz)
            drawLine(
                color = CreamMuted.copy(alpha = 0.07f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1f,
            )
            val label = if (hz >= 1000f) "${(hz / 1000f).roundToInt()}k" else "${hz.roundToInt()}"
            val text = textMeasurer.measure(label, axisStyle)
            drawText(
                text,
                topLeft = Offset(
                    (x + 2f).coerceAtMost(size.width - text.size.width),
                    size.height - text.size.height,
                ),
            )
        }

        // Barras: cada una ocupa el hueco logarítmico hasta la siguiente frecuencia.
        val count = magnitudes.size
        for (i in 0 until count) {
            val hz = frequencies.getOrNull(i) ?: continue
            val nextHz = frequencies.getOrNull(i + 1) ?: topHz
            val x0 = xForHz(hz)
            val x1 = if (i + 1 < count) xForHz(nextHz) else size.width
            val width = (x1 - x0 - 0.6f).coerceAtLeast(0.6f)
            val db = magnitudes[i]
            val h = yForDb(db)
            if (h <= 0.5f) continue
            val below = cutoffHz <= 0f || hz <= cutoffHz
            val intensity = ((db - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
            val color = if (below) {
                Gold.copy(alpha = 0.30f + 0.68f * intensity)
            } else {
                Color(0xFFE07A5F).copy(alpha = 0.10f + 0.35f * intensity)
            }
            drawRect(
                color = color,
                topLeft = Offset(x0, size.height - h),
                size = Size(width, h),
            )
        }

        // Línea de corte medida.
        if (cutoffHz > 0f) {
            val x = xForHz(cutoffHz)
            var y = 0f
            while (y < size.height) {
                drawLine(
                    color = Color(0xFFE07A5F),
                    start = Offset(x, y),
                    end = Offset(x, (y + 6f).coerceAtMost(size.height)),
                    strokeWidth = 2.4f,
                )
                y += 11f
            }
            val cutLabel = textMeasurer.measure(
                "cut ${"%.1f".format(cutoffHz / 1000f)}k",
                labelStyle,
            )
            drawText(
                cutLabel,
                topLeft = Offset(
                    (x + 3f).coerceAtMost(size.width - cutLabel.size.width),
                    1f,
                ),
            )
        }

        // Referencia de 20 kHz cuando el archivo llega más arriba.
        if (topHz > 21_000f) {
            val x = xForHz(20_000f)
            drawLine(
                color = Cream.copy(alpha = 0.30f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1.2f,
            )
        }
    }
}
