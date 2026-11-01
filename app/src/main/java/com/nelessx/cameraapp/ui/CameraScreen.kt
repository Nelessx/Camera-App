package com.nelessx.cameraapp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.opengl.GLSurfaceView
import android.view.OrientationEventListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
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
import com.nelessx.cameraapp.camera.findMainBackCamera
import com.nelessx.cameraapp.viewfinder.FrameMailbox
import com.nelessx.cameraapp.viewfinder.RawViewfinderRenderer
import com.nelessx.cameraapp.viewfinder.ViewParams
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val ErrorRed = Color(0xFFFF6B6B)

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
    val error by controller.error.collectAsState()
    val captureState by controller.captureState.collectAsState()
    var settings by remember { mutableStateOf(CaptureSettings()) }
    var mosaic by remember { mutableStateOf(false) }
    var gamma by remember { mutableStateOf(true) }
    var whiteBalance by remember { mutableStateOf(false) }
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

    val viewParams = ViewParams(
        mosaic = mosaic,
        gamma = gamma,
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
            OverlayText(
                text = "CAM ${specs.id} · RAW ${rawSize.width}×${rawSize.height} · view ${viewFps.roundToInt()} fps" +
                    if (zoom > 1.01f) {
                        String.format(Locale.US, " · 1 photosite = %.1f px", zoom * glView.width / sensorAcross)
                    } else {
                        ""
                    },
                modifier = Modifier.align(Alignment.TopStart),
            )
            OverlayText(text = liveSummary(stats), modifier = Modifier.align(Alignment.BottomStart))
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
                Box(modifier = Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp).background(Color.DarkGray))
                // Viewing only: these change how sensor values are drawn, never the saved DNG
                ChipToggle("Mosaic", selected = mosaic) { mosaic = !mosaic }
                ChipToggle("Gamma", selected = gamma) { gamma = !gamma }
                ChipToggle("WB", selected = whiteBalance) { whiteBalance = !whiteBalance }
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
            ShutterButton(
                enabled = captureState != CaptureState.Capturing,
                onClick = controller::capture,
            )
            StatusLine(captureState, error)
        }
    }
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

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color.DarkGray)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Capture RAW photo" },
    )
}

@Composable
private fun StatusLine(captureState: CaptureState, error: String?) {
    val (text, color) = when {
        error != null -> error to ErrorRed
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
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

@Composable
private fun OverlayText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        modifier = modifier
            .padding(8.dp)
            .background(Color(0x99000000), RoundedCornerShape(4.dp))
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

private fun formatFps(frameDurationNs: Long): String {
    if (frameDurationNs <= 0) return "–"
    return String.format(Locale.US, "%.1f fps", 1e9 / frameDurationNs)
}

/** Focus distance in diopters is 1/metres; 0 means infinity. */
private fun formatFocus(diopters: Float): String =
    if (diopters < 0.01f) "∞" else String.format(Locale.US, "%.2f m", 1 / diopters)
