package com.nelessx.cameraapp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.view.OrientationEventListener
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.nelessx.cameraapp.camera.CameraController
import com.nelessx.cameraapp.camera.CameraSpecs
import com.nelessx.cameraapp.camera.CaptureSettings
import com.nelessx.cameraapp.camera.CaptureState
import com.nelessx.cameraapp.camera.FrameStats
import com.nelessx.cameraapp.camera.findMainBackCamera
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
            CameraPreview()
        } else {
            PermissionRequest(onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) })
        }
    }
}

@Composable
private fun CameraPreview() {
    val context = LocalContext.current
    val specs = remember { findMainBackCamera(context.getSystemService(CameraManager::class.java)) }
    if (specs == null) {
        CenteredMessage("No back camera found")
        return
    }

    val controller = remember { CameraController(context, specs) }
    DisposableEffect(controller) {
        // The screen is locked to portrait, so the DNG's orientation comes from how the phone is physically held
        val orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation != ORIENTATION_UNKNOWN) controller.deviceOrientation = orientation
            }
        }
        orientationListener.enable()
        onDispose {
            orientationListener.disable()
            controller.release()
        }
    }
    val stats by controller.stats.collectAsState()
    val error by controller.error.collectAsState()
    val captureState by controller.captureState.collectAsState()
    var settings by remember { mutableStateOf(CaptureSettings()) }

    fun update(newSettings: CaptureSettings) {
        settings = newSettings
        controller.updateSettings(newSettings)
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Box(
            // The app is portrait and the sensor is landscape, so the frame is shown rotated: width and height swap
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(specs.previewSize.height.toFloat() / specs.previewSize.width),
        ) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        keepScreenOn = true
                        // Must be a size the camera supports, set before the surface is created
                        holder.setFixedSize(specs.previewSize.width, specs.previewSize.height)
                        // The surface only exists while the app is visible, so it drives the camera's lifetime
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = controller.open(holder.surface)
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                            override fun surfaceDestroyed(holder: SurfaceHolder) = controller.close()
                        })
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            OverlayText(
                text = "CAM ${specs.id} · ${specs.hardwareLevel} · RAW " +
                    (specs.rawSize?.let { "${it.width}×${it.height}" } ?: "n/a"),
                modifier = Modifier.align(Alignment.TopStart),
            )
            OverlayText(text = liveSummary(stats), modifier = Modifier.align(Alignment.BottomStart))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ModeSelector(
                manual = settings.manual,
                manualAvailable = specs.supportsManual,
                onChange = { manual -> update(switchMode(settings, manual, stats, specs)) },
            )
            if (specs.supportsManual) {
                ManualControls(specs, settings, stats, onChange = ::update)
            }
            Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                ShutterButton(
                    enabled = specs.rawSize != null && captureState != CaptureState.Capturing,
                    onClick = controller::capture,
                )
            }
            StatusLine(specs, captureState, error)
        }
    }
}

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

@Composable
private fun ModeSelector(manual: Boolean, manualAvailable: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !manual, onClick = { onChange(false) }, label = { Text("Auto") })
        FilterChip(
            selected = manual,
            onClick = { onChange(true) },
            label = { Text("Manual") },
            enabled = manualAvailable,
        )
    }
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
private fun StatusLine(specs: CameraSpecs, captureState: CaptureState, error: String?) {
    val (text, color) = when {
        error != null -> error to ErrorRed
        specs.rawSize == null -> "This camera has no RAW output" to ErrorRed
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
