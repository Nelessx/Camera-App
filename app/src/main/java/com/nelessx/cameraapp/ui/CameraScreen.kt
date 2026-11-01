package com.nelessx.cameraapp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.nelessx.cameraapp.camera.CameraController
import com.nelessx.cameraapp.camera.CameraSpecs
import com.nelessx.cameraapp.camera.FrameStats
import com.nelessx.cameraapp.camera.findMainBackCamera
import java.util.Locale
import kotlin.math.roundToLong

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
    val manager = remember { context.getSystemService(CameraManager::class.java) }
    val specs = remember { findMainBackCamera(manager) }
    if (specs == null) {
        CenteredMessage("No back camera found")
        return
    }

    val controller = remember { CameraController(manager, specs.id) }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    val stats by controller.stats.collectAsState()
    val error by controller.error.collectAsState()

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
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
            // The app is portrait and the sensor is landscape, so the frame is shown rotated: width and height swap
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(specs.previewSize.height.toFloat() / specs.previewSize.width),
        )
        InfoPanel(specs, stats, error)
    }
}

@Composable
private fun InfoPanel(specs: CameraSpecs, stats: FrameStats?, error: String?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        InfoRow("Camera", "${specs.id} · ${specs.hardwareLevel}")
        InfoRow("RAW", specs.rawSize?.let { "${it.width}×${it.height}" } ?: "not supported")
        InfoRow("Preview", "${specs.previewSize.width}×${specs.previewSize.height}")
        InfoRow("ISO", stats?.iso?.toString() ?: "–")
        InfoRow("Shutter", stats?.let { formatShutter(it.exposureNs) } ?: "–")
        InfoRow("Frame rate", stats?.let { formatFps(it.frameDurationNs) } ?: "–")
        if (error != null) {
            Text(error, color = Color(0xFFFF6B6B), fontSize = 14.sp)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Color.Gray, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
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
