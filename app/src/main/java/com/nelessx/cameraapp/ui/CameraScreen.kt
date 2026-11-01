package com.nelessx.cameraapp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.opengl.GLSurfaceView
import android.view.OrientationEventListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nelessx.cameraapp.camera.CameraController
import com.nelessx.cameraapp.camera.CameraSpecs
import com.nelessx.cameraapp.camera.CaptureSettings
import com.nelessx.cameraapp.camera.CaptureState
import com.nelessx.cameraapp.camera.FrameStats
import com.nelessx.cameraapp.camera.HISTOGRAM_BINS
import com.nelessx.cameraapp.camera.RawHistogram
import com.nelessx.cameraapp.camera.findMainBackCamera
import com.nelessx.cameraapp.video.RecordingStats
import com.nelessx.cameraapp.video.VideoMode
import com.nelessx.cameraapp.viewfinder.FrameMailbox
import com.nelessx.cameraapp.viewfinder.RawViewfinderRenderer
import com.nelessx.cameraapp.viewfinder.ViewParams
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

private val ErrorRed = Color(0xFFFF6B6B)
private val ClipMagenta = Color(0xFFFF4FFF)
private val RecordRed = Color(0xFFE53935)
private val ChannelColours = listOf(Color(0xFFFF5252), Color(0xFF4CE07A), Color(0xFF4C8DFF))

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasPermission) {
            RawCamera()
        } else {
            PermissionRequest(onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) })
        }
    }
}

@Composable
private fun RawCamera() {
    val context = LocalContext.current
    val specs = remember { findMainBackCamera(context.getSystemService(CameraManager::class.java)) }
    val rawSize = specs?.rawSize
    if (specs == null || rawSize == null) {
        CenteredMessage(if (specs == null) "No back camera found" else "This camera has no RAW output")
        return
    }

    val mailbox = remember { FrameMailbox(rawSize.width, rawSize.height) }
    val renderer = remember { RawViewfinderRenderer(mailbox, specs) }
    val glView = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            keepScreenOn = true
        }
    }
    val controller = remember { CameraController(context, specs, mailbox) }

    // The camera streams only while the app is in the foreground
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    glView.onResume()
                    controller.open()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    controller.close()
                    glView.onPause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(controller) {
        mailbox.onFrameAvailable = { glView.requestRender() }
        // The screen is locked to portrait, so the DNG's orientation comes from how the phone is physically held
        val orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation != ORIENTATION_UNKNOWN) controller.deviceOrientation = orientation
            }
        }
        orientationListener.enable()
        onDispose {
            orientationListener.disable()
            mailbox.onFrameAvailable = null
            controller.release()
        }
    }

    val stats by controller.stats.collectAsState()
    val histogram by controller.histogram.collectAsState()
    val error by controller.error.collectAsState()
    val captureState by controller.captureState.collectAsState()
    val recording by controller.recording.collectAsState()
    var settings by remember { mutableStateOf(CaptureSettings()) }
    var videoFormat by remember { mutableStateOf(VideoMode.WINDOW_1080) }
    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> audioGranted = granted }
    val isRecording = recording?.let { !it.stopping && !it.finished } == true
    val isFinishing = recording?.let { it.stopping && !it.finished } == true
    var mosaic by remember { mutableStateOf(false) }
    var gamma by remember { mutableStateOf(true) }
    var whiteBalance by remember { mutableStateOf(false) }
    var clipWarning by remember { mutableStateOf(true) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var centerX by remember { mutableFloatStateOf(0.5f) }
    var centerY by remember { mutableFloatStateOf(0.5f) }
    var viewFps by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(renderer) {
        while (true) {
            delay(500)
            viewFps = renderer.drawnFps
        }
    }

    fun update(newSettings: CaptureSettings) {
        settings = newSettings
        controller.updateSettings(newSettings)
    }

    fun setVideo(video: Boolean) {
        update(settings.copy(video = video))
        // Sound is optional: without the permission, videos are recorded silent
        if (video && !audioGranted) audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val viewParams = ViewParams(
        mosaic = mosaic,
        gamma = gamma,
        clipWarning = clipWarning,
        gainR = if (whiteBalance) stats?.gainR ?: 1f else 1f,
        gainG = if (whiteBalance) stats?.gainG ?: 1f else 1f,
        gainB = if (whiteBalance) stats?.gainB ?: 1f else 1f,
        zoom = zoom,
        centerX = centerX,
        centerY = centerY,
    )
    // Photosites across the view's width: the portrait screen shows the sensor's short side across
    val sensorAcross = if (specs.sensorOrientation % 180 == 90) rawSize.height else rawSize.width

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Box(
            // The sensor is landscape and the app portrait, so the frame is shown rotated: width and height swap
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(
                    if (specs.sensorOrientation % 180 == 90) rawSize.height.toFloat() / rawSize.width
                    else rawSize.width.toFloat() / rawSize.height
                ),
        ) {
            AndroidView(
                factory = { glView },
                update = { view ->
                    renderer.params = viewParams
                    view.requestRender()
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Gestures: pinch to zoom, drag to pan, double-tap to toggle 1 photosite = 1 screen pixel
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, gestureZoom, _ ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val maxZoom = 4f * sensorAcross / w
                            val newZoom = (zoom * gestureZoom).coerceIn(1f, maxZoom)
                            // Keep the point under the fingers in place while zooming, then follow the drag
                            val fx = centerX + (centroid.x / w - 0.5f) / zoom
                            val fy = centerY + (centroid.y / h - 0.5f) / zoom
                            centerX = clampCenter(fx - (centroid.x / w - 0.5f) / newZoom - pan.x / w / newZoom, newZoom)
                            centerY = clampCenter(fy - (centroid.y / h - 0.5f) / newZoom - pan.y / h / newZoom, newZoom)
                            zoom = newZoom
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { tap ->
                            if (zoom > 1.01f) {
                                zoom = 1f
                                centerX = 0.5f
                                centerY = 0.5f
                            } else {
                                val oneToOne = sensorAcross / size.width.toFloat()
                                zoom = oneToOne
                                centerX = clampCenter(tap.x / size.width, oneToOne)
                                centerY = clampCenter(tap.y / size.height, oneToOne)
                            }
                        })
                    },
            )
            // Outline of the area a windowed video records (only meaningful when not zoomed in)
            if (settings.video && videoFormat != VideoMode.FULL_FRAME && zoom <= 1.01f) {
                val region = videoFormat.region(rawSize)
                val rotated = specs.sensorOrientation % 180 == 90
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(if (rotated) region.height().toFloat() / rawSize.height else region.width().toFloat() / rawSize.width)
                        .fillMaxHeight(if (rotated) region.width().toFloat() / rawSize.width else region.height().toFloat() / rawSize.height)
                        .border(1.5.dp, Color.White.copy(alpha = 0.8f)),
                )
            }
            val rec = recording
            if (rec != null && !rec.finished) {
                OverlayText(
                    text = recordingSummary(rec),
                    modifier = Modifier.align(Alignment.TopStart),
                    background = Color(0xCCD32F2F),
                )
            } else {
                OverlayText(
                    text = "CAM ${specs.id} · RAW ${rawSize.width}×${rawSize.height} · view ${viewFps.roundToInt()} fps" +
                        if (zoom > 1.01f) {
                            String.format(Locale.US, " · 1 photosite = %.1f px", zoom * glView.width / sensorAcross)
                        } else {
                            ""
                        },
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
            OverlayText(text = liveSummary(stats), modifier = Modifier.align(Alignment.BottomStart))
            // Viewing only: these change how sensor values are drawn, never the saved DNG
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.End,
            ) {
                ViewToggle("MOSAIC", mosaic) { mosaic = it }
                ViewToggle("GAMMA", gamma) { gamma = it }
                ViewToggle("WB", whiteBalance) { whiteBalance = it }
                ViewToggle("CLIP", clipWarning) { clipWarning = it }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ChipToggle("Auto", selected = !settings.manual) { update(switchMode(settings, false, stats, specs)) }
                ChipToggle("Manual", selected = settings.manual, enabled = specs.supportsManual) {
                    update(switchMode(settings, true, stats, specs))
                }
                RawHistogramView(
                    histogram = histogram,
                    blackLevel = specs.blackLevels.average().toFloat(),
                    whiteLevel = specs.whiteLevel,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }
            if (specs.supportsManual) {
                ManualControls(specs, settings, stats, onChange = ::update)
            }
        }
        // Pinned below the scrolling controls so the shutter is always reachable
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val busy = isRecording || isFinishing
            // Compact chips: two stacked fit beside the shutter
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ChipToggle("Photo", selected = !settings.video, enabled = !busy) { setVideo(false) }
                        ChipToggle("Video", selected = settings.video, enabled = !busy) { setVideo(true) }
                    }
                    ShutterButton(
                        style = when {
                            !settings.video -> ShutterStyle.PHOTO
                            isRecording -> ShutterStyle.RECORDING
                            else -> ShutterStyle.VIDEO
                        },
                        enabled = if (settings.video) !isFinishing else captureState != CaptureState.Capturing,
                        onClick = {
                            when {
                                !settings.video -> controller.capture()
                                isRecording -> controller.stopRecording()
                                else -> controller.startRecording(videoFormat, audioGranted)
                            }
                        },
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        if (settings.video) {
                            VideoMode.entries.forEach { format ->
                                ChipToggle(format.label, selected = videoFormat == format, enabled = !busy) {
                                    videoFormat = format
                                }
                            }
                        }
                    }
                }
            }
            StatusLine(captureState, error, settings.video, if (settings.video) recording else null)
        }
    }
}

private fun recordingSummary(stats: RecordingStats): String {
    val seconds = stats.durationNs / 1_000_000_000L
    val base = String.format(
        Locale.US, "● REC %d:%02d · %d frames · %.2f GB",
        seconds / 60, seconds % 60, stats.frames, stats.bytes / 1e9,
    )
    return when {
        stats.stopping -> "Writing remaining frames · ${stats.frames} done"
        stats.dropped > 0 -> "$base · ${stats.dropped} dropped"
        else -> base
    }
}

/**
 * Raw value distribution per colour channel on a linear 0..whiteLevel axis, counts on a square-root
 * scale so smaller populations stay visible next to the dark peak. The grey line marks the black level.
 */
@Composable
private fun RawHistogramView(histogram: RawHistogram?, blackLevel: Float, whiteLevel: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF111111)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val blackX = blackLevel / (whiteLevel + 1) * size.width
            drawLine(Color.DarkGray, Offset(blackX, 0f), Offset(blackX, size.height), strokeWidth = 1.dp.toPx())
            if (histogram == null) return@Canvas
            val peak = histogram.bins.maxOf { it.max() }.toFloat()
            if (peak <= 0f) return@Canvas
            val binWidth = size.width / HISTOGRAM_BINS
            histogram.bins.forEachIndexed { channel, counts ->
                val outline = Path().apply {
                    moveTo(0f, size.height)
                    counts.forEachIndexed { i, count ->
                        val top = size.height * (1f - sqrt(count / peak))
                        lineTo(i * binWidth, top)
                        lineTo((i + 1) * binWidth, top)
                    }
                    lineTo(size.width, size.height)
                }
                drawPath(outline, ChannelColours[channel], alpha = 0.25f)
                drawPath(outline, ChannelColours[channel], style = Stroke(width = 1.5.dp.toPx()))
            }
        }
        val clippedText = histogram?.clipped
            ?.mapIndexedNotNull { channel, fraction ->
                if (fraction > 0f) "${"RGB"[channel]} ${formatPercent(fraction)}" else null
            }
            ?.joinToString(" ")
        Text(
            text = when {
                histogram == null -> ""
                clippedText.isNullOrEmpty() -> "no clipping"
                else -> "clip $clippedText"
            },
            color = if (clippedText.isNullOrEmpty()) Color.Gray else ClipMagenta,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

/** Small on/off switch drawn over the viewfinder. */
@Composable
private fun ViewToggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Text(
        label,
        color = if (on) Color.Black else Color.White,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (on) Color.White else Color(0x99000000))
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun ChipToggle(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 13.sp) },
        enabled = enabled,
    )
}

private fun clampCenter(value: Float, zoom: Float): Float = value.coerceIn(0.5f / zoom, 1f - 0.5f / zoom)

/** Switching to manual starts from whatever auto-exposure last chose, so the image doesn't jump. */
private fun switchMode(current: CaptureSettings, manual: Boolean, stats: FrameStats?, specs: CameraSpecs): CaptureSettings {
    val isoRange = specs.isoRange
    val exposureRange = specs.exposureRangeNs
    if (!manual || stats == null || isoRange == null || exposureRange == null) return current.copy(manual = manual)
    return current.copy(
        manual = true,
        iso = isoRange.clamp(stats.iso),
        exposureNs = exposureRange.clamp(stats.exposureNs),
        focusDiopters = stats.focusDiopters.coerceIn(0f, specs.minFocusDiopters),
    )
}

/** In auto mode the sliders are disabled and follow what the camera chose; in manual mode they drive it. */
@Composable
private fun ManualControls(
    specs: CameraSpecs,
    settings: CaptureSettings,
    stats: FrameStats?,
    onChange: (CaptureSettings) -> Unit,
) {
    val isoRange = specs.isoRange ?: return
    val exposureRange = specs.exposureRangeNs ?: return
    val isoMin = isoRange.lower.toDouble()
    val isoMax = isoRange.upper.toDouble()
    val expMin = exposureRange.lower.toDouble()
    val expMax = exposureRange.upper.toDouble()

    val iso = if (settings.manual) settings.iso else stats?.iso
    ControlSlider(
        label = "ISO",
        valueText = iso?.toString() ?: "–",
        position = iso?.let { toLogPosition(it.toDouble(), isoMin, isoMax) } ?: 0f,
        enabled = settings.manual,
        onChange = { onChange(settings.copy(iso = fromLogPosition(it, isoMin, isoMax).roundToInt())) },
    )

    val exposure = if (settings.manual) settings.exposureNs else stats?.exposureNs
    ControlSlider(
        label = "Shutter",
        valueText = exposure?.let { formatShutter(it) } ?: "–",
        position = exposure?.let { toLogPosition(it.toDouble(), expMin, expMax) } ?: 0f,
        enabled = settings.manual,
        onChange = { onChange(settings.copy(exposureNs = fromLogPosition(it, expMin, expMax).roundToLong())) },
    )

    if (specs.minFocusDiopters > 0f) {
        val focus = if (settings.manual) settings.focusDiopters else stats?.focusDiopters
        ControlSlider(
            label = "Focus",
            valueText = focus?.let { formatFocus(it) } ?: "–",
            position = focus?.let { (it / specs.minFocusDiopters).coerceIn(0f, 1f) } ?: 0f,
            enabled = settings.manual,
            onChange = { onChange(settings.copy(focusDiopters = it * specs.minFocusDiopters)) },
        )
    }
}

@Composable
private fun ControlSlider(
    label: String,
    valueText: String,
    position: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.Gray, fontSize = 14.sp, modifier = Modifier.width(60.dp))
        Slider(value = position, onValueChange = onChange, enabled = enabled, modifier = Modifier.weight(1f))
        Text(
            valueText,
            color = Color.White,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.width(84.dp),
        )
    }
}

private enum class ShutterStyle { PHOTO, VIDEO, RECORDING }

@Composable
private fun ShutterButton(style: ShutterStyle, enabled: Boolean, onClick: () -> Unit) {
    val recording = style == ShutterStyle.RECORDING
    Box(
        modifier = Modifier
            .size(72.dp)
            .border(4.dp, Color.White, CircleShape)
            // While recording the button becomes a smaller square: tap to stop
            .padding(if (recording) 20.dp else 8.dp)
            .clip(if (recording) RoundedCornerShape(6.dp) else CircleShape)
            .background(
                when {
                    !enabled -> Color.DarkGray
                    style == ShutterStyle.PHOTO -> Color.White
                    else -> RecordRed
                }
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = when (style) {
                    ShutterStyle.PHOTO -> "Capture RAW photo"
                    ShutterStyle.VIDEO -> "Start raw video recording"
                    ShutterStyle.RECORDING -> "Stop recording"
                }
            },
    )
}

@Composable
private fun StatusLine(captureState: CaptureState, error: String?, video: Boolean, recording: RecordingStats?) {
    val (text, color) = when {
        error != null -> error to ErrorRed
        recording?.error != null -> recording.error to ErrorRed
        recording != null && recording.finished ->
            "Saved ${recording.frames} frames" +
                (if (recording.dropped > 0) " (${recording.dropped} dropped)" else "") +
                " to ${recording.folder}" to Color.White
        recording != null -> "Recording to ${recording.folder}" to Color.Gray
        video -> "Videos are saved as DNG sequences in Android/data/…/files/Movies" to Color.Gray
        captureState is CaptureState.Capturing -> "Saving DNG…" to Color.White
        captureState is CaptureState.Saved -> "Saved ${captureState.location}" to Color.White
        captureState is CaptureState.Failed -> captureState.message to ErrorRed
        else -> "Photos are saved as DNG in DCIM/CameraApp" to Color.Gray
    }
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, end = 16.dp),
    )
}

@Composable
private fun OverlayText(text: String, modifier: Modifier = Modifier, background: Color = Color(0x99000000)) {
    Text(
        text,
        color = Color.White,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        modifier = modifier
            .padding(8.dp)
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Camera access is needed to show the sensor preview.",
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequest, modifier = Modifier.padding(top = 16.dp)) {
            Text("Grant camera access")
        }
        Text(
            "If nothing happens, enable Camera for this app in system Settings.",
            color = Color.Gray,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White)
    }
}

private fun liveSummary(stats: FrameStats?): String {
    if (stats == null) return "starting…"
    return "ISO ${stats.iso} · ${formatShutter(stats.exposureNs)} · " +
        "${formatFps(stats.frameDurationNs)} · ${formatFocus(stats.focusDiopters)}"
}

/** Log scale, so every doubling of ISO or shutter time gets the same slider travel. */
private fun toLogPosition(value: Double, min: Double, max: Double): Float =
    (ln(value / min) / ln(max / min)).toFloat().coerceIn(0f, 1f)

private fun fromLogPosition(position: Float, min: Double, max: Double): Double =
    min * (max / min).pow(position.toDouble())

private fun formatShutter(exposureNs: Long): String {
    if (exposureNs <= 0) return "–"
    val seconds = exposureNs / 1e9
    return if (seconds >= 0.5) {
        String.format(Locale.US, "%.1f s", seconds)
    } else {
        "1/${(1 / seconds).roundToLong()} s"
    }
}

private fun formatPercent(fraction: Float): String =
    if (fraction < 0.001f) "<0.1%" else String.format(Locale.US, "%.1f%%", fraction * 100)

private fun formatFps(frameDurationNs: Long): String {
    if (frameDurationNs <= 0) return "–"
    return String.format(Locale.US, "%.1f fps", 1e9 / frameDurationNs)
}

/** Focus distance in diopters is 1/metres; 0 means infinity. */
private fun formatFocus(diopters: Float): String =
    if (diopters < 0.01f) "∞" else String.format(Locale.US, "%.2f m", 1 / diopters)
