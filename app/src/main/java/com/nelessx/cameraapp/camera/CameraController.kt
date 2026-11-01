package com.nelessx.cameraapp.camera

import android.annotation.SuppressLint
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/** Values the sensor actually used for a frame, as reported in its CaptureResult. */
data class FrameStats(
    val iso: Int,
    val exposureNs: Long,
    val frameDurationNs: Long,
)

// The UI only needs a few updates per second, not one per frame
private const val STATS_INTERVAL_NS = 100_000_000L

/**
 * Owns one Camera2 device and its preview session.
 *
 * All camera state lives on a dedicated background thread; [open], [close] and [release]
 * can be called from any thread and just post work to it.
 */
class CameraController(private val manager: CameraManager, private val cameraId: String) {

    private val thread = HandlerThread("CameraThread").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    // Bumped on every open/close so callbacks belonging to an earlier open() are ignored
    private var generation = 0
    private var lastStatsTimeNs = 0L

    private val _stats = MutableStateFlow<FrameStats?>(null)
    val stats: StateFlow<FrameStats?> = _stats.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Opens the camera and streams preview frames into [previewSurface]. */
    // Only called once CameraScreen has confirmed the CAMERA permission
    @SuppressLint("MissingPermission")
    fun open(previewSurface: Surface) {
        handler.post {
            closeInternal()
            val gen = generation
            _error.value = null
            try {
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (gen != generation) {
                            camera.close()
                            return
                        }
                        device = camera
                        startPreview(camera, previewSurface, gen)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (gen == generation) {
                            device = null
                            _error.value = "Camera disconnected (another app may be using it)"
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (gen == generation) {
                            device = null
                            _error.value = "Camera error $error"
                        }
                    }
                }, handler)
            } catch (e: CameraAccessException) {
                _error.value = "Cannot open camera: ${e.message}"
            } catch (e: SecurityException) {
                _error.value = "Camera permission missing"
            }
        }
    }

    /** Stops streaming and frees the camera so other apps can use it. */
    fun close() {
        handler.post { closeInternal() }
    }

    /** Closes the camera and stops the background thread. The controller can't be reused afterwards. */
    fun release() {
        handler.post { closeInternal() }
        thread.quitSafely()
    }

    private fun startPreview(camera: CameraDevice, surface: Surface, gen: Int) {
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                if (gen != generation) {
                    configured.close()
                    return
                }
                session = configured
                try {
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                    }.build()
                    configured.setRepeatingRequest(request, captureCallback, handler)
                } catch (e: CameraAccessException) {
                    _error.value = "Cannot start preview: ${e.message}"
                } catch (e: IllegalStateException) {
                    // The device was closed between configuring and starting; nothing to do
                }
            }

            override fun onConfigureFailed(failed: CameraCaptureSession) {
                if (gen == generation) _error.value = "Could not configure camera session"
            }
        }
        try {
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(surface)),
                    executor,
                    callback,
                )
            )
        } catch (e: CameraAccessException) {
            _error.value = "Cannot create session: ${e.message}"
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - lastStatsTimeNs < STATS_INTERVAL_NS) return
            lastStatsTimeNs = now
            _stats.value = FrameStats(
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L,
            )
        }
    }

    private fun closeInternal() {
        generation++
        session?.close()
        session = null
        device?.close()
        device = null
        _stats.value = null
    }
}
