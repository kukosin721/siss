@file:OptIn(ExperimentalMaterial3Api::class)

package com.kukosin.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kukosin.app.eq.BiquadDesign
import com.kukosin.app.eq.EqBandType
import com.kukosin.app.eq.EqManager
import com.kukosin.app.eq.EqPresets
import com.kukosin.app.eq.EqSettings
import com.kukosin.app.eq.formatFrequency
import com.kukosin.app.eq.formatGain
import com.kukosin.app.ui.theme.Cream
import com.kukosin.app.ui.theme.CreamMuted
import com.kukosin.app.ui.theme.Gold
import com.kukosin.app.ui.theme.Ink
import com.kukosin.app.ui.theme.InkSoft
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow

/** Rango visible de la curva, en dB (las bandas llegan a ±12 y el preamp a ±12). */
private const val DISPLAY_DB = 15f
private const val MIN_F = 20f
private const val MAX_F = 20_000f
private val CURVE_SAMPLE_RATE = EqSettings.REFERENCE_SAMPLE_RATE

/**
 * Ecualizador gráfico de 10 bandas.
 *
 * - Curva de respuesta calculada de verdad (|H(e^jw)| de la cascada de biquads), no una
 *   interpolación de los sliders: lo que ves es lo que suena.
 * - Arrastrar sobre la curva mueve la banda más cercana.
 * - Preamp manual + headroom automático, vúmetro de pico con indicador de clip.
 * - Presets sobre las frecuencias ISO de octava.
 */
@Composable
internal fun EqualizerWorkspace(
    bottomPadding: Int,
    onBackToPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by EqManager.settings.collectAsState()
    val activeProfile by EqManager.profile.collectAsState()
    val meter by EqManager.meter.collectAsState()
    val clipping by EqManager.clipping.collectAsState()
    var selectedBand by remember { mutableIntStateOf(2) }
    var showBandAdvanced by remember { mutableStateOf(false) }

    // Vúmetro: el hilo de audio sólo acumula; acá se consume a 20 Hz. El aviso de clip
    // se apaga solo tras ~1,5 s sin picos altos (si no, queda rojo para siempre).
    LaunchedEffect(Unit) {
        var calm = 0
        while (true) {
            val peakTick = EqManager.consumePeak()
            if (!peakTick.second && peakTick.first < 0.95f) {
                calm++
                if (calm >= 30) {
                    EqManager.clearClipIndicator()
                    calm = 0
                }
            } else {
                calm = 0
            }
            delay(50)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize(),
        color = Ink,
        contentColor = Cream,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 18.dp, vertical = 10.dp)
                .padding(bottom = bottomPadding.dp),
        ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBackToPlayer) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = Gold,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Equalizer",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Gold,
                        )
                        Text(
                            "10 bands · constant Q · 64-bit · ${activeProfile.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CreamMuted,
                        )
                    }
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = { EqManager.setEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Ink,
                            checkedTrackColor = Gold,
                            uncheckedThumbColor = CreamMuted,
                            uncheckedTrackColor = InkSoft,
                        ),
                    )
                }

                Spacer(Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        EqCurveCard(
                            settings = settings,
                            selectedBand = selectedBand,
                            onSelectBand = { selectedBand = it },
                            meter = meter,
                            clipping = clipping,
                        )
                    }

                    item {
                        BandChips(
                            settings = settings,
                            selectedBand = selectedBand,
                            onSelect = { selectedBand = it },
                        )
                    }

                    item {
                        BandEditor(
                            settings = settings,
                            index = selectedBand,
                            showAdvanced = showBandAdvanced,
                            onToggleAdvanced = { showBandAdvanced = !showBandAdvanced },
                        )
                    }

                    item {
                        PresetRow(settings = settings)
                    }

                    item {
                        PreampCard(settings = settings)
                    }

                    item {
                        InfoCard(settings = settings)
                    }

                    item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

// --------------------------------------------------------------------- curva

@Composable
private fun EqCurveCard(
    settings: EqSettings,
    selectedBand: Int,
    onSelectBand: (Int) -> Unit,
    meter: Float,
    clipping: Boolean,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = CreamMuted, fontSize = 9.sp)
    val gridColor = Cream.copy(alpha = 0.07f)
    val dim = if (settings.enabled) 1f else 0.45f
    val curveAlpha by animateFloatAsState(
        targetValue = dim,
        animationSpec = Motion.leave(),
        label = "curveAlpha",
    )

    Surface(
        color = InkSoft,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(228.dp)
                    .pointerInput(settings.bands.size) {
                        detectTapGestures { offset ->
                            val hit = nearestBand(offset.x, size.width.toFloat(), settings)
                            if (hit >= 0) onSelectBand(hit)
                        }
                    }
                    .pointerInput(settings.bands.size) {
                        var active = -1
                        detectDragGestures(
                            onDragStart = { offset ->
                                active = nearestBand(offset.x, size.width.toFloat(), settings)
                                if (active >= 0) onSelectBand(active)
                                if (active >= 0) {
                                    val g = gainForY(offset.y, size.height.toFloat())
                                    EqManager.setBandGain(active, g)
                                }
                            },
                            onDrag = { change, _ ->
                                if (active >= 0) {
                                    change.consume()
                                    val g = gainForY(change.position.y, size.height.toFloat())
                                    EqManager.setBandGain(active, g)
                                }
                            },
                            onDragEnd = { active = -1 },
                            onDragCancel = { active = -1 },
                        )
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    // Misma escala log que la curva (t = 0 → 20 Hz, t = 1 → 20 kHz), si no
                    // las líneas de la rejilla y los pomos caen corridos respecto de la curva.
                    val logSpan = log10(MAX_F / MIN_F)
                    val xOf = { f: Float -> (log10(f) - log10(MIN_F)) / logSpan * w }
                    val yOf = { db: Float -> h / 2f - (db / DISPLAY_DB) * (h / 2f) }

                    // Rejilla horizontal (±6, ±12 dB) y línea de 0 dB.
                    listOf(-12f, -6f, 6f, 12f).forEach { db ->
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, yOf(db)),
                            end = Offset(w, yOf(db)),
                            strokeWidth = 1f,
                        )
                    }
                    drawLine(
                        color = Cream.copy(alpha = 0.18f),
                        start = Offset(0f, yOf(0f)),
                        end = Offset(w, yOf(0f)),
                        strokeWidth = 1.4f,
                    )

                    // Rejilla vertical + etiquetas de frecuencia.
                    val marks = listOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)
                    marks.forEach { f ->
                        val x = xOf(f)
                        drawLine(
                            color = gridColor,
                            start = Offset(x, 0f),
                            end = Offset(x, h),
                            strokeWidth = 1f,
                        )
                        val label = if (f >= 1000f) "${(f / 1000f).toInt()}k" else f.toInt().toString()
                        val layout = textMeasurer.measure(label, labelStyle)
                        val lx = (x - layout.size.width / 2f).coerceIn(0f, w - layout.size.width)
                        drawText(layout, topLeft = Offset(lx, h - layout.size.height - 1f))
                    }

                    // Curva de respuesta real.
                    val steps = 200
                    val path = Path()
                    var peakDb = -60f
                    for (i in 0..steps) {
                        val t = i.toFloat() / steps
                        val f = MIN_F * (MAX_F / MIN_F).pow(t)
                        val db = BiquadDesign.responseDb(settings, f.toDouble(), CURVE_SAMPLE_RATE).toFloat()
                        if (db > peakDb) peakDb = db
                        val x = t * w
                        val y = yOf(db.coerceIn(-DISPLAY_DB, DISPLAY_DB))
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }

                    val fill = Path().apply {
                        addPath(path)
                        lineTo(w, yOf(0f))
                        lineTo(0f, yOf(0f))
                        close()
                    }
                    drawPath(
                        path = fill,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Gold.copy(alpha = 0.30f * curveAlpha),
                                Gold.copy(alpha = 0.02f * curveAlpha),
                            ),
                            startY = 0f,
                            endY = yOf(0f),
                        ),
                    )
                    drawPath(
                        path = path,
                        color = Gold.copy(alpha = curveAlpha),
                        style = Stroke(width = 2.4f, cap = StrokeCap.Round),
                    )

                    // Nodos de banda.
                    settings.bands.forEachIndexed { index, band ->
                        val x = xOf(band.freqHz)
                        val y = yOf(band.gainDb.coerceIn(-DISPLAY_DB, DISPLAY_DB))
                        val selected = index == selectedBand
                        val radius = if (selected) 8f else 5f
                        drawCircle(
                            color = if (band.gainDb != 0f) Gold.copy(alpha = curveAlpha) else CreamMuted.copy(alpha = 0.7f),
                            radius = radius,
                            center = Offset(x, y),
                        )
                        drawCircle(
                            color = Ink,
                            radius = if (selected) 3.2f else 1.8f,
                            center = Offset(x, y),
                        )
                        if (selected) {
                            drawCircle(
                                color = Gold.copy(alpha = 0.35f * curveAlpha),
                                radius = 15f,
                                center = Offset(x, y),
                                style = Stroke(width = 1.5f),
                            )
                        }
                    }

                    // Etiqueta del pico de la curva (headroom).
                    if (settings.enabled && peakDb > 0.2f) {
                        val txt = "+${(peakDb * 10).toInt() / 10f} dB"
                        val layout = textMeasurer.measure(
                            txt,
                            TextStyle(color = Gold, fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        )
                        drawText(layout, topLeft = Offset(6f, 2f))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            PeakMeterRow(meter = meter, clipping = clipping, settings = settings)
        }
    }
}

@Composable
private fun PeakMeterRow(meter: Float, clipping: Boolean, settings: EqSettings) {
    val dbText = remember(meter) {
        if (meter <= 0.0001f) "-∞" else {
            val db = 20 * log10(meter.toDouble())
            val rounded = Math.round(db * 10.0) / 10.0
            "$rounded"
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            "PREAMP ${formatGain(settings.effectivePreampDb)}",
            style = MaterialTheme.typography.labelSmall,
            color = CreamMuted,
            modifier = Modifier.width(126.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .background(Ink, RoundedCornerShape(3.dp)),
        ) {
            val fraction = (meter / 1.0f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(CreamMuted, Gold, Color(0xFFFF6B6B)),
                        ),
                        RoundedCornerShape(3.dp),
                    ),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "$dbText dB",
            style = MaterialTheme.typography.labelSmall,
            color = if (clipping) Color(0xFFFF6B6B) else CreamMuted,
            modifier = Modifier.width(52.dp),
        )
    }
}

// -------------------------------------------------------------------- bandas

@Composable
private fun BandChips(settings: EqSettings, selectedBand: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        settings.bands.forEachIndexed { index, band ->
            val selected = index == selectedBand
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    formatFrequency(band.freqHz),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) Ink else CreamMuted,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .background(
                            if (selected) Gold else InkSoft,
                            RoundedCornerShape(7.dp),
                        )
                        .clickable { onSelect(index) }
                        .padding(horizontal = 4.dp, vertical = 5.dp),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    if (abs(band.gainDb) < 0.05f) "0" else formatGain(band.gainDb).replace(" dB", ""),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    color = if (abs(band.gainDb) < 0.05f) CreamMuted.copy(alpha = 0.5f) else Gold,
                )
            }
        }
    }
}

@Composable
private fun BandEditor(
    settings: EqSettings,
    index: Int,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
) {
    val band = settings.bands.getOrNull(index) ?: return
    Surface(color = InkSoft, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Band ${index + 1} · ${freqLabel(band.freqHz)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Cream,
                    )
                    Text(
                        when (band.type) {
                            EqBandType.LOW_SHELF -> "Low shelf · gentle slope"
                            EqBandType.HIGH_SHELF -> "High shelf · gentle slope"
                            EqBandType.PEAK -> "Constant-Q bell"
                            else -> "Filter"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = CreamMuted,
                    )
                }
                Text(
                    formatGain(band.gainDb),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Gold,
                )
            }

            Spacer(Modifier.height(4.dp))
            EqSlider(
                value = band.gainDb,
                min = -EqSettings.MAX_GAIN_DB,
                max = EqSettings.MAX_GAIN_DB,
                label = "Gain",
                onChange = { EqManager.setBandGain(index, it) },
            )

            Text(
                if (showAdvanced) "Hide frequency and Q" else "Band frequency and Q",
                style = MaterialTheme.typography.labelSmall,
                color = Gold,
                modifier = Modifier
                    .clickable { onToggleAdvanced() }
                    .padding(vertical = 6.dp),
            )

            if (showAdvanced) {
                BandTypeSelector(
                    current = band.type,
                    onSelect = { EqManager.setBandType(index, it) },
                )
                EqSlider(
                    value = log10(band.freqHz),
                    min = log10(EqSettings.MIN_FREQ_HZ),
                    max = log10(EqSettings.MAX_FREQ_HZ),
                    label = "Frequency ${freqLabel(band.freqHz)}",
                    onChange = { EqManager.setBandFrequency(index, 10f.pow(it)) },
                )
                if (band.type == EqBandType.PEAK) {
                    EqSlider(
                        value = log2(band.q),
                        min = log2(EqSettings.MIN_Q),
                        max = log2(EqSettings.MAX_Q),
                        label = "Q ${(Math.round(band.q * 100f) / 100f)}",
                        onChange = { EqManager.setBandQ(index, 2f.pow(it)) },
                    )
                }
            }

            val offending = settings.maxBoostDb
            if (offending > 0.05f && !settings.autoHeadroom) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Maximum boost +${(Math.round(offending * 10f) / 10f)} dB without auto headroom: " +
                        "lower the preamp to -${(Math.round(offending * 10f) / 10f)} dB to avoid clipping.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFB26B),
                )
            }
        }
    }
}

@Composable
private fun EqSlider(
    value: Float,
    min: Float,
    max: Float,
    label: String,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = CreamMuted)
        Slider(
            value = value.coerceIn(min, max),
            valueRange = min..max,
            onValueChange = onChange,
            colors = SliderDefaults.colors(
                thumbColor = Gold,
                activeTrackColor = Gold,
                inactiveTrackColor = Ink,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.height(34.dp),
        )
    }
}

// ------------------------------------------------------------------- presets

@Composable
private fun PresetRow(settings: EqSettings) {
    Column {
        Text(
            "Presets",
            style = MaterialTheme.typography.labelLarge,
            color = Gold,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(EqPresets.all) { preset ->
                val selected = settings.presetName == preset.name
                Text(
                    preset.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) Ink else Cream,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .background(if (selected) Gold else InkSoft, RoundedCornerShape(20.dp))
                        .border(
                            1.dp,
                            if (selected) Gold else Cream.copy(alpha = 0.10f),
                            RoundedCornerShape(20.dp),
                        )
                        .clickable { EqManager.applyPreset(preset.name) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(InkSoft, RoundedCornerShape(20.dp))
                        .clickable { EqManager.resetFlat() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = null,
                        tint = Gold,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Flat",
                        style = MaterialTheme.typography.labelMedium,
                        color = Cream,
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------- preamp

@Composable
private fun PreampCard(settings: EqSettings) {
    Surface(color = InkSoft, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Preamp and headroom",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Cream,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "The preamp sets the level before the chain. With auto headroom, " +
                    "every dB the bands boost is subtracted here: EQ can never clip.",
                style = MaterialTheme.typography.bodySmall,
                color = CreamMuted,
            )
            Spacer(Modifier.height(6.dp))
            EqSlider(
                value = settings.preampDb,
                min = -EqSettings.MAX_GAIN_DB,
                max = EqSettings.MAX_GAIN_DB,
                label = "Manual preamp ${formatGain(settings.preampDb)}",
                onChange = { EqManager.setPreamp(it) },
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Automatic headroom",
                        style = MaterialTheme.typography.labelLarge,
                        color = Cream,
                    )
                    Text(
                        "Total attenuation: ${formatGain(settings.effectivePreampDb)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CreamMuted,
                    )
                }
                Switch(
                    checked = settings.autoHeadroom,
                    onCheckedChange = { EqManager.setAutoHeadroom(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Ink,
                        checkedTrackColor = Gold,
                        uncheckedThumbColor = CreamMuted,
                        uncheckedTrackColor = Ink,
                    ),
                )
            }
        }
    }
}

@Composable
private fun InfoCard(settings: EqSettings) {
    Surface(color = InkSoft, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "About processing",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Cream,
            )
            Spacer(Modifier.height(6.dp))
            Bullet("Cascaded RBJ biquads, computed in 64-bit with a 12 dB range per band.")
            Bullet("Parameters smoothed at audio rate: moving a slider produces no zipper noise.")
            Bullet("With the EQ off or flat the processor does not touch the signal: the stream stays bit-perfect.")
            if (settings.enabled) {
                Bullet(
                    "With the EQ active bit-perfect is paused: the signal goes through the " +
                        "filter chain (float/24-bit, no intermediate quantization).",
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Each band is a constant-Q bell (or a shelf at the ends), " +
                    "so boosting +3 dB does not widen the filter.",
                style = MaterialTheme.typography.labelSmall,
                color = CreamMuted,
            )
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(4.dp)
                .background(Gold, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = CreamMuted)
    }
}

// ------------------------------------------------------------------ utilidades

/** "62 Hz" / "1 kHz": formatFrequency devuelve sin unidad. */
private fun freqLabel(hz: Float): String =
    if (hz >= 1_000f) "${formatFrequency(hz)}Hz" else "${formatFrequency(hz)} Hz"

private fun nearestBand(x: Float, width: Float, settings: EqSettings): Int {
    if (width <= 0f) return -1
    var best = -1
    var bestDist = Float.MAX_VALUE
    val logSpan = log10((MAX_F / MIN_F).toDouble())
    val logMin = log10(MIN_F.toDouble())
    settings.bands.forEachIndexed { index, band ->
        val bx = ((log10(band.freqHz.toDouble()) - logMin) / logSpan).toFloat() * width
        val d = abs(bx - x)
        if (d < bestDist) {
            bestDist = d
            best = index
        }
    }
    return best
}

private fun gainForY(y: Float, height: Float): Float {
    if (height <= 0f) return 0f
    val db = ((height / 2f - y) / (height / 2f)) * DISPLAY_DB
    return db.coerceIn(-EqSettings.MAX_GAIN_DB, EqSettings.MAX_GAIN_DB)
}

/**
 * Tipo de filtro de la banda: campana (Q constante) o shelf en los extremos.
 *
 * Los presets y el ecualizador de fábrica usan shelf en 31 Hz y 16 kHz porque ahí es
 * donde suena natural; con esto se puede cambiar a gusto de cada uno.
 */
@Composable
private fun BandTypeSelector(current: EqBandType, onSelect: (EqBandType) -> Unit) {
    val options = listOf(
        EqBandType.LOW_SHELF to "Low shelf",
        EqBandType.PEAK to "Bell",
        EqBandType.HIGH_SHELF to "High shelf",
    )
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            "Filter type",
            style = MaterialTheme.typography.labelSmall,
            color = CreamMuted,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (type, label) ->
                val selected = type == current
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) Ink else CreamMuted,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .background(if (selected) Gold else InkSoft, RoundedCornerShape(10.dp))
                        .border(1.dp, if (selected) Gold else Cream.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                        .clickable { onSelect(type) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}
