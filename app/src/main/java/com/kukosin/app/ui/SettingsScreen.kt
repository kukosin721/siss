@file:OptIn(UnstableApi::class)

package com.kukosin.app.ui

import com.kukosin.app.playback.ContinuityPreset
import androidx.media3.common.util.UnstableApi
import android.Manifest
import android.content.ComponentName
import android.net.Uri
import android.util.Log
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.kukosin.app.ui.component.KukosinWaveformSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import com.kukosin.app.data.artwork.EmbeddedArtwork
import com.kukosin.app.data.ytm.YtmService
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.kukosin.app.data.AlbumGroup
import com.kukosin.app.data.ArtistGroup
import com.kukosin.app.data.FolderNode
import com.kukosin.app.data.LibraryInsights
import com.kukosin.app.data.Track
import com.kukosin.app.data.formatTime
import com.kukosin.app.data.ytm.YtmTrack
import com.kukosin.app.audio.AudioEngineStatus
import com.kukosin.app.playback.AudioConfig
import com.kukosin.app.playback.AudioRoute
import com.kukosin.app.playback.OutputMode
import com.kukosin.app.playback.OutputDevice
import com.kukosin.app.playback.PlaybackService
import com.kukosin.app.playback.QueueManager
import com.kukosin.app.playback.UsbDacManager
import com.kukosin.app.ui.theme.Cream
import com.kukosin.app.ui.theme.CreamMuted
import com.kukosin.app.ui.theme.AccentPreset
import com.kukosin.app.ui.theme.AccentTheme
import com.kukosin.app.ui.theme.Gold
import com.kukosin.app.ui.theme.Line
import com.kukosin.app.ui.theme.accentColor
import com.kukosin.app.ui.theme.Ink
import com.kukosin.app.ui.theme.InkElevated
import com.kukosin.app.ui.theme.InkSoft
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
@Composable
internal fun FullScreenSettingsScreen(
    visibilityState: MutableTransitionState<Boolean>,
    output: OutputDevice,
    autoAnalyze: Boolean,
    onToggleAutoAnalyze: () -> Unit,
    outputMode: OutputMode,
    onSelectOutputMode: (OutputMode) -> Unit,
    strictBitPerfect: Boolean,
    onToggleStrictBitPerfect: () -> Unit,
    fades: Boolean,
    onToggleFades: () -> Unit,
    bufferMs: Int,
    onSelectBufferMs: (Int) -> Unit,
    continuity: ContinuityPreset,
    onSelectContinuity: (ContinuityPreset) -> Unit,
    volumeCurve: Int,
    onSelectVolumeCurve: (Int) -> Unit,
    decoder: String,
    onSelectDecoder: (String) -> Unit,
    usbPermissionGranted: Boolean,
    onRequestUsbPermission: () -> Unit,
    accentHue: Float,
    onPreviewAccent: (Float) -> Unit,
    onSelectAccent: (AccentPreset) -> Unit,
    onCommitAccent: () -> Unit,
    onResetAccent: () -> Unit,
    animationSpeed: AnimationSpeed,
    onSelectAnimationSpeed: (AnimationSpeed) -> Unit,
    blurStrength: BlurStrength,
    onSelectBlurStrength: (BlurStrength) -> Unit,
    fontOption: FontWeightOption,
    onSelectFontOption: (FontWeightOption) -> Unit,
    onRescan: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Estado real del motor nativo: lo que suena, no lo que pedimos.
    val engineStatus by AudioEngineStatus.status.collectAsState()
    val engineInfo = engineStatus.info
    val engineStateText = if (engineInfo.opened) "Open · ${engineInfo.apiLabel}" else "No open stream"
    LaunchedEffect(engineInfo.opened) {
        while (true) {
            AudioEngineStatus.refresh()
            delay(500)
        }
    }

    val transition = rememberTransition(visibilityState, label = "settingsScreen")
    val reveal by transition.animateFloat(
        transitionSpec = {
            if (targetState) Motion.enter(Motion.ENTER_MS) else Motion.leave()
        },
        label = "settingsReveal",
    ) { visible -> if (visible) 1f else 0f }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = reveal
                    translationY = (1f - reveal) * 32.dp.toPx()
                    val scale = 0.975f + 0.025f * reveal
                    scaleX = scale
                    scaleY = scale
                },
            color = Ink,
            contentColor = Cream,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { hapticTick(); onDismiss() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Gold, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Gold)
                }

                Spacer(Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        Text("Audio Output Mode", style = MaterialTheme.typography.labelLarge, color = Gold, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = InkSoft,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Signal path to the DAC", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Cream)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Five paths with their own semantics. Oboe is disabled by default while " +
                                        "its audio problems are fixed: every mode except Disabled plays through " +
                                        "it, the exclusive ones add a hard requirement, and bit-perfect only " +
                                        "exists with a DAC: the built-in speaker always goes through the " +
                                        "phone's DSP and amplifier.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CreamMuted,
                                )
                                Spacer(Modifier.height(10.dp))
                                OutputMode.entriesInOrder.forEach { mode ->
                                    val selected = outputMode == mode
                                    val modeEnabled = !mode.requiresDac || output.isDac
                                    Surface(
                                        color = when {
                                            selected -> Gold.copy(alpha = 0.14f)
                                            modeEnabled -> Ink
                                            else -> Ink.copy(alpha = 0.55f)
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            when {
                                                selected -> Gold
                                                modeEnabled -> Cream.copy(alpha = 0.10f)
                                                else -> Cream.copy(alpha = 0.05f)
                                            },
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = modeEnabled) { onSelectOutputMode(mode) },
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.Top,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    mode.label,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = when {
                                                        selected -> Gold
                                                        modeEnabled -> Cream
                                                        else -> CreamMuted.copy(alpha = 0.45f)
                                                    },
                                                )
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    mode.description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (modeEnabled) CreamMuted else CreamMuted.copy(alpha = 0.4f),
                                                )
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    mode.volumeHint,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = when {
                                                        selected -> Gold
                                                        modeEnabled -> CreamMuted.copy(alpha = 0.7f)
                                                        else -> CreamMuted.copy(alpha = 0.35f)
                                                    },
                                                )
                                            }
                                            if (selected) {
                                                Spacer(Modifier.width(8.dp))
                                                Icon(
                                                    Icons.Rounded.Check,
                                                    contentDescription = null,
                                                    tint = Gold,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            } else if (!modeEnabled) {
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    "Connect a DAC",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = CreamMuted.copy(alpha = 0.45f),
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }

                                if (!output.isDac) {
                                    Text(
                                        "No DAC detected: exclusive output modes stay locked until a DAC is connected. " +
                                            "Playback stays on the Android mixer because Oboe is disabled by default.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFFFB26B),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }

                                if (outputMode.requiresUsb && !output.isUsb) {
                                    Text(
                                        "No USB DAC connected: this mode has nowhere to open the stream. " +
                                            "The app keeps playing, but it cannot be bit-perfect.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFFFB26B),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }

                                if (outputMode.needsUsbPermission && !usbPermissionGranted) {
                                    Text(
                                        "This mode needs USB permission to take over the DAC.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFFFB26B),
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Surface(
                                        color = Gold,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onRequestUsbPermission() },
                                    ) {
                                        Text(
                                            "Grant USB permission",
                                            color = Ink,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelLarge,
                                            modifier = Modifier.padding(vertical = 10.dp),
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }

                                Spacer(Modifier.height(4.dp))
                                SettingsStatusLine("Status", engineStateText)
                                SettingsStatusLine("API", engineInfo.apiLabel)
                                SettingsStatusLine("Device", engineStatus.deviceName.ifBlank { output.name })
                                SettingsStatusLine("Stream", engineInfo.streamLabel)
                                SettingsStatusLine(
                                    "Bit-perfect now",
                                    when {
                                        engineStatus.bitPerfectNow -> "Yes · exclusive, no conversion, volume 100 %"
                                        engineStatus.mode == OutputMode.DISABLED -> "No · Disabled mode (Android mixer)"
                                        engineStatus.builtinSpeaker -> "No · the built-in speaker cannot be bit-perfect"
                                        engineStatus.eqActive -> "No · equalizer active (64-bit DSP)"
                                        !engineInfo.opened -> "No open stream"
                                        !engineInfo.exclusive -> "No · the mixer shares the output"
                                        engineInfo.converted -> "No · converted to the DAC format"
                                        engineInfo.volumePercent < 100 -> "No · volume at ${engineInfo.volumePercent} %"
                                        else -> "No"
                                    },
                                    highlight = engineStatus.bitPerfectNow,
                                )
                                if (engineInfo.opened) {
                                    SettingsStatusLine(
                                        "Buffer",
                                        "${engineInfo.bufferFrames} frames · " +
                                            "${Math.round(engineInfo.bufferMs)} ms · HAL " +
                                            "${Math.round(engineInfo.halBufferMs)} ms · " +
                                            "burst ${engineInfo.framesPerBurst}",
                                    )
                                    SettingsStatusLine(
                                        "Path",
                                        if (engineInfo.mmap) "MMAP (no mixer, no DSP)" else
                                            if (engineInfo.exclusive) "Exclusive (HAL)" else "Shared (mixer)",
                                    )
                                    if (engineInfo.latencyMs >= 0) {
                                        SettingsStatusLine(
                                            "Latency",
                                            "${Math.round(engineInfo.latencyMs)} ms (measured with AAudio timestamp)",
                                        )
                                    }
                                    SettingsStatusLine(
                                        "Xruns",
                                        if (engineInfo.xRuns < 0) "Not reported" else engineInfo.xRuns.toString(),
                                    )
                                    if (engineInfo.xRuns > 0) {
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            "There were ${engineInfo.xRuns} xruns: the engine ran out of data or the system " +
                                                "did not service it in time. If you hear a click, raise the output buffer.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFFFFB26B),
                                        )
                                    }
                                }
                                engineStatus.message?.let { message ->
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFFF6B6B),
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Text("Sample Rates Supported by the DAC Hardware", style = MaterialTheme.typography.labelLarge, color = Gold, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = InkSoft,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(output.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Gold)
                                Spacer(Modifier.height(4.dp))
                                Text("Sample rates reported by the active output:", style = MaterialTheme.typography.bodySmall, color = CreamMuted)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = output.sampleRatesLabel.ifBlank { "44.1 kHz, 48.0 kHz, 88.2 kHz, 96.0 kHz, 176.4 kHz, 192.0 kHz, 384.0 kHz" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Cream,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }

                    item {
                        Text("Strict Bit-Perfect", style = MaterialTheme.typography.labelLarge, color = Gold, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = InkSoft,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Never convert", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Cream)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "If the DAC does not accept the exact format, playback fails and reports it instead of " +
                                            "resampling behind your back. Off (recommended) tries bit-perfect first and " +
                                            "only converts with the engine's Kaiser resampler when unavoidable.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CreamMuted,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Switch(
                                    checked = strictBitPerfect,
                                    onCheckedChange = { onToggleStrictBitPerfect() },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Ink,
                                        checkedTrackColor = Gold,
                                        uncheckedThumbColor = CreamMuted,
                                        uncheckedTrackColor = InkSoft,
                                    ),
                                )
                            }
                        }
                    }

                    item {
                        Text("Fade In and Out", style = MaterialTheme.typography.labelLarge, color = Gold, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = InkSoft,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Smooth fades", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Cream)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "25 ms on start and 60 ms on pause/stop, plus a short fade after " +
                                            "each seek: no clicks or hard cuts. Turn it off if you want the signal intact " +
                                            "from the first frame (gain is not 100 % during the fade).",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CreamMuted,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Switch(
                                    checked = fades,
                                    onCheckedChange = { onToggleFades() },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Ink,
                                        checkedTrackColor = Gold,
                                        uncheckedThumbColor = CreamMuted,
                                        uncheckedTrackColor = InkSoft,
                                    ),
                                )
                            }
                        }
                    }

                    item {
                        Text("No gaps between tracks", style = MaterialTheme.typography.labelLarge, color = Gold, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = InkSoft,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Skip and seek response", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Cream)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    continuity.description +
                                        " Applies buffer, start-up preroll and fades in one go, so a skip " +
                                        "reaches the DAC as early as the device allows.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CreamMuted,
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    ContinuityPreset.entries.forEach { option ->
                                        val selected = continuity == option
                                        Surface(
                                            color = if (selected) Gold else Ink,
                                            shape = RoundedCornerShape(10.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Gold),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { onSelectContinuity(option) },
                                        ) {
                                            Text(
                                                text = option.label,
                                                color = if (selected) Ink else Gold,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(18.dp))
                                Text("Volume curve", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Cream)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Perceptual spreads the slider evenly over its whole range instead of " +
                                        "jumping in the last third. At 100 % the gain is exactly unity either way, " +
                                        "so bit-perfect output does not change.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CreamMuted,
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    listOf(1 to "Perceptual", 0 to "Linear").forEach { (value, label) ->
                                        val selected = volumeCurve == value
                                        Surface(
                                            color = if (selected) Gold else Ink,
                                            shape = RoundedCornerShape(10.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Gold),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { onSelectVolumeCurve(value) },
                                        ) {
                                            Text(
                                                text = label,
                                                color = if (selected) Ink else Gold,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(vertical = 8.dp),
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text("Output Buffer", style = MaterialTheme.typography.labelLarge, color = Gold, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = InkSoft,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Engine internal buffer ($bufferMs ms)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Cream)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "This is the engine's in-memory buffer, the one that absorbs decoder " +
                                        "hiccups. The AAudio hardware buffer stays at the minimum (2 bursts) " +
                                        "so a seek is instant: draining it costs nothing.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CreamMuted,
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    AudioConfig.BUFFER_OPTIONS.forEach { option ->
                                        val selected = bufferMs == option
                                        Surface(
                                            color = if (selected) Gold else Ink,
                                            shape = RoundedCornerShape(10.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Gold),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { onSelectBufferMs(option) },
                                        ) {
                                            Text(
                                                text = "$option ms",
                                                color = if (selected) Ink else Gold,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(vertical = 8.dp),
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text("Decoder", style = MaterialTheme.typography.labelLarge, color = Gold, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = InkSoft,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Real PCM bit depth", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Cream)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Defines the PCM bit depth coming out of the decoder. The system decoder " +
                                        "(MediaCodec) delivers the file's real depth; FFmpeg is more compatible with " +
                                        "unusual formats (APE, DSD, WavPack) but outputs 16-bit.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CreamMuted,
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    listOf("Automatic", "MediaCodec", "FFmpeg (Lossless)").forEach { option ->
                                        val selected = decoder == option
                                        Surface(
                                            color = if (selected) Gold else Ink,
                                            shape = RoundedCornerShape(10.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Gold),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { onSelectDecoder(option) },
                                        ) {
                                            Text(
                                                text = option,
                                                color = if (selected) Ink else Gold,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text("Spectral Analysis and Authenticity", style = MaterialTheme.typography.labelLarge, color = Gold, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = InkSoft,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Automatic Spectral Analysis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Cream)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Decode PCM samples and compute a 4096-point FFT spectrum automatically while tracks play.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CreamMuted,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Switch(
                                    checked = autoAnalyze,
                                    onCheckedChange = { onToggleAutoAnalyze() },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Ink,
                                        checkedTrackColor = Gold,
                                        uncheckedThumbColor = CreamMuted,
                                        uncheckedTrackColor = InkSoft,
                                    ),
                                )
                            }
                        }
                    }

                    item {
                        Text("Library and Deduplication", style = MaterialTheme.typography.labelLarge, color = Gold, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = InkSoft,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Track Deduplication Active", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Cream)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Smart filtering by file path to avoid duplicate songs between MediaStore Audio and MediaStore Files.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CreamMuted,
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        onRescan()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Rescan Full Library", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    item {
                        Text("Personalization", style = MaterialTheme.typography.labelLarge, color = Gold, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = InkSoft,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Accent Color", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Cream)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "The factory gold can be changed to another tone. It applies to the whole app and is saved.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CreamMuted,
                                )
                                Spacer(Modifier.height(14.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(AccentPreset.entries.toList()) { preset ->
                                        val selected = AccentTheme.isSelected(preset)
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable { onSelectAccent(preset) }
                                                .padding(vertical = 4.dp, horizontal = 2.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(accentColor(preset.hueDegrees))
                                                    .then(
                                                        if (selected) {
                                                            Modifier.border(2.dp, Cream, CircleShape)
                                                        } else {
                                                            Modifier
                                                        },
                                                    ),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                if (selected) {
                                                    Icon(
                                                        Icons.Rounded.Check,
                                                        contentDescription = null,
                                                        tint = Ink,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                preset.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (selected) Cream else CreamMuted,
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(14.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("Fine hue", style = MaterialTheme.typography.labelMedium, color = CreamMuted)
                                    Text(
                                        "${accentHue.toInt()}°",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Gold,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Slider(
                                    value = accentHue,
                                    onValueChange = onPreviewAccent,
                                    onValueChangeFinished = onCommitAccent,
                                    valueRange = 0f..360f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = Gold,
                                        activeTrackColor = Gold,
                                        inactiveTrackColor = Line,
                                    ),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    Text(
                                        "Back to gold",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Gold,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onResetAccent() }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Text("Animations", style = MaterialTheme.typography.labelLarge, color = Gold, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = InkSoft,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Animation speed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Cream)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Scales every transition and animation in the app. Off makes them instant.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CreamMuted,
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    AnimationSpeed.entries.forEach { option ->
                                        val selected = animationSpeed == option
                                        Surface(
                                            color = if (selected) Gold else Ink,
                                            shape = RoundedCornerShape(10.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Gold),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { onSelectAnimationSpeed(option) },
                                        ) {
                                            Text(
                                                text = option.label,
                                                color = if (selected) Ink else Gold,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(18.dp))
                                Text("Transition blur", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Cream)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    blurStrength.description +
                                        " Blur is the most expensive effect in the app: Off is also the fastest. " +
                                        "Navigation blur is the moving one: it only exists during a screen " +
                                        "transition and never costs a pass while the screen is still.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CreamMuted,
                                    modifier = Modifier.animateContentSize(Motion.quick()),
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    BlurStrength.entries.forEach { option ->
                                        val selected = blurStrength == option
                                        Surface(
                                            color = if (selected) Gold else Ink,
                                            shape = RoundedCornerShape(10.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Gold),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { onSelectBlurStrength(option) },
                                        ) {
                                            Text(
                                                text = option.label,
                                                color = if (selected) Ink else Gold,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text("Typography", style = MaterialTheme.typography.labelLarge, color = Gold, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = InkSoft,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Font weight", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Cream)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Default keeps Google Sans regular. Bold renders all base text in bold.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CreamMuted,
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    FontWeightOption.entries.forEach { option ->
                                        val selected = fontOption == option
                                        Surface(
                                            color = if (selected) Gold else Ink,
                                            shape = RoundedCornerShape(10.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Gold),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { onSelectFontOption(option) },
                                        ) {
                                            Text(
                                                text = option.label,
                                                color = if (selected) Ink else Gold,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

/** Fila etiqueta/valor del panel del motor. */
@Composable
internal fun SettingsStatusLine(label: String, value: String, highlight: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = CreamMuted,
            modifier = Modifier.weight(0.36f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (highlight) Gold else Cream,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.weight(0.64f),
            textAlign = TextAlign.End,
        )
    }
}

internal fun audioPermissions(): Array<String> {
    val permissions = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions += Manifest.permission.READ_MEDIA_AUDIO
        permissions += Manifest.permission.POST_NOTIFICATIONS
    } else {
        permissions += Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return permissions.toTypedArray()
}

internal fun hasAudioPermission(context: android.content.Context): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
