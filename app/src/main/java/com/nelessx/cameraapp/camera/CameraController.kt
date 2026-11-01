package com.nelessx.cameraapp.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ExifInterface
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.OrientationEventListener
import com.nelessx.cameraapp.viewfinder.FrameMailbox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** What the user has chosen; applied to every frame. */
data class CaptureSettings(
    val manual: Boolean = false,
    val iso: Int = 100,
    val exposureNs: Long = 10_000_000L,
    val focusDiopters: Float = 0f,
)

/** Values the sensor actually used for a frame, as reported in its CaptureResult. */
data class FrameStats(
    val iso: Int,
    val exposureNs: Long,
    val frameDurationNs: Long,
    val focusDiopters: Float,
    /** The camera's white balance estimate. Only a hint for viewing and for raw editors. */
    val gainR: Float,
    val gainG: Float,
    val gainB: Float,
)

sealed interface CaptureState {
    data object Idle : CaptureState
    data object Capturing : CaptureState
    data class Saved(val location: String) : CaptureState
    data class Failed(val message: String) : CaptureState
}

// The UI only needs a few updates per second, not one per frame
private const val STATS_INTERVAL_NS = 100_000_000L

// Results are kept briefly so a captured frame can be matched with its metadata, whichever arrives first
private const val RECENT_RESULTS = 8

/**
 * Owns one Camera2 device streaming nothing but full-size RAW frames. Every frame goes to the
 * viewfinder through [viewfinder]; a capture saves the next frame as a DNG.
 *
 * All camera state lives on a dedicated background thread; the public methods can be called
 * from any thread and just post work to it. DNG files are written on a separate thread so slow
 * storage never stalls camera callbacks.
 */
class CameraController(
    context: Context,
    private val specs: CameraSpecs,
    private val viewfinder: FrameMailbox,
) {

    private val manager = context.getSystemService(CameraManager::class.java)
    private val characteristics = manager.getCameraCharacteristics(specs.id)
    private val rawSize = requireNotNull(specs.rawSize) { "Camera ${specs.id} has no RAW output" }
    private val saver = DngSaver(context)

    private val thread = HandlerThread("CameraThread").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val saveExecutor = Executors.newSingleThreadExecutor()

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null

    // Bumped on every open/close so callbacks belonging to an earlier open() are ignored
    private var generation = 0
    private var lastStatsTimeNs = 0L
    private val recentResults = ArrayDeque<TotalCaptureResult>()

    @Volatile
    private var settings = CaptureSettings()
    private val settingsUpdateQueued = AtomicBoolean(false)

    // One capture at a time: the next frame is copied, then saved once its metadata has arrived too
    private var capturing = false
    private var captureNextFrame = false
    private var saving = false
    private var pendingFrame: RawFrame? = null
    private var pendingOrientation = ExifInterface.ORIENTATION_NORMAL

    /** Physical device rotation in degrees, from an OrientationEventListener; tags DNG orientation. */
    @Volatile
    var deviceOrientation = 0

    private val _stats = MutableStateFlow<FrameStats?>(null)
    val stats: StateFlow<FrameStats?> = _stats.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    /** Opens the camera and starts streaming RAW frames. */
    // Only called once CameraScreen has confirmed the CAMERA permission
    @SuppressLint("MissingPermission")
    fun open() {
        handler.post {
            closeInternal()
            val gen = generation
            _error.value = null
            try {
                manager.openCamera(specs.id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (gen != generation) {
                            camera.close()
                            return
                        }
                        device = camera
                        startSession(camera, gen)
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

    /** Applies new settings from the next frame on. */
    fun updateSettings(newSettings: CaptureSettings) {
        settings = newSettings
        // Slider drags fire many updates; only queue one request rebuild at a time
        if (settingsUpdateQueued.compareAndSet(false, true)) {
            handler.post {
                settingsUpdateQueued.set(false)
                startRepeating()
            }
        }
    }

    /** Saves the next RAW frame as a DNG. */
    fun capture() {
        handler.post {
            if (session == null) {
                _captureState.value = CaptureState.Failed("Camera not ready")
                return@post
            }
            if (capturing) return@post
            capturing = true
            captureNextFrame = true
            pendingFrame = null
            pendingOrientation = exifOrientation(deviceOrientation)
            _captureState.value = CaptureState.Capturing
        }
    }

    /** Stops streaming and frees the camera so other apps can use it. */
    fun close() {
        handler.post { closeInternal() }
    }

    /**
     * Closes the camera and stops the background threads. A DNG that is already being written
     * still finishes. The controller can't be reused afterwards.
     */
    fun release() {
        handler.post { closeInternal() }
        thread.quitSafely()
        saveExecutor.shutdown()
    }

    private fun startSession(camera: CameraDevice, gen: Int) {
        val reader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 4).apply {
            setOnImageAvailableListener({ onRawImage(it) }, handler)
        }
        rawReader = reader

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                if (gen != generation) {
                    configured.close()
                    return
                }
                session = configured
                startRepeating()
            }

            override fun onConfigureFailed(failed: CameraCaptureSession) {
                if (gen == generation) _error.value = "Could not configure camera session"
            }
        }
        // RAW is the only output: no ISP-processed stream exists, so nothing processed reaches the screen
        val outputs = listOf(OutputConfiguration(reader.surface))
        try {
            camera.createCaptureSession(
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback)
            )
        } catch (e: CameraAccessException) {
            _error.value = "Cannot create session: ${e.message}"
        }
    }

    /** (Re)starts the RAW stream with the current settings. Runs on the camera thread. */
    private fun startRepeating() {
        val activeSession = session ?: return
        val reader = rawReader ?: return
        try {
            val request = activeSession.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                applySettings(settings)
            }.build()
            activeSession.setRepeatingRequest(request, frameCallback, handler)
        } catch (e: CameraAccessException) {
            _error.value = "Cannot start camera: ${e.message}"
        } catch (e: IllegalStateException) {
            // The session was closed in the meantime; nothing to do
        }
    }

    private fun CaptureRequest.Builder.applySettings(s: CaptureSettings) {
        // Switch off every ISP stage this camera allows. Most only affect processed output, which this
        // session doesn't have, but some devices apply hot pixel or shading correction to RAW too.
        setOffIfAvailable(
            CaptureRequest.NOISE_REDUCTION_MODE,
            CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
            CameraMetadata.NOISE_REDUCTION_MODE_OFF,
        )
        setOffIfAvailable(
            CaptureRequest.EDGE_MODE,
            CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES,
            CameraMetadata.EDGE_MODE_OFF,
        )
        setOffIfAvailable(
            CaptureRequest.HOT_PIXEL_MODE,
            CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES,
            CameraMetadata.HOT_PIXEL_MODE_OFF,
        )
        setOffIfAvailable(
            CaptureRequest.SHADING_MODE,
            CameraCharacteristics.SHADING_AVAILABLE_MODES,
            CameraMetadata.SHADING_MODE_OFF,
        )
        setOffIfAvailable(
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
            CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
            CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF,
        )
        setOffIfAvailable(
            CaptureRequest.DISTORTION_CORRECTION_MODE,
            CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES,
            CameraMetadata.DISTORTION_CORRECTION_MODE_OFF,
        )

        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        // White balance stays automatic: for RAW it is only a metadata hint, never applied to the pixels
        set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        val canFocus = specs.minFocusDiopters > 0f
        if (s.manual) {
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_SENSITIVITY, s.iso)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, s.exposureNs)
            set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(s.exposureNs, specs.minFrameDurationNs))
            if (canFocus) {
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDiopters)
            }
        } else {
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
            if (canFocus && CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes) {
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
        }
    }

    private fun CaptureRequest.Builder.setOffIfAvailable(
        key: CaptureRequest.Key<Int>,
        availableModes: CameraCharacteristics.Key<IntArray>,
        off: Int,
    ) {
        if (characteristics.get(availableModes)?.contains(off) == true) set(key, off)
    }

    private val frameCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            recentResults.addLast(result)
            if (recentResults.size > RECENT_RESULTS) recentResults.removeFirst()
            finishCaptureIfReady()

            val now = SystemClock.elapsedRealtimeNanos()
            if (now - lastStatsTimeNs < STATS_INTERVAL_NS) return
            lastStatsTimeNs = now
            val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            _stats.value = FrameStats(
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L,
                focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
                gainR = gains?.red ?: 1f,
                gainG = gains?.let { (it.greenEven + it.greenOdd) / 2 } ?: 1f,
                gainB = gains?.blue ?: 1f,
            )
        }
    }

    private fun onRawImage(reader: ImageReader) {
        // Only the newest frame matters for the viewfinder; older queued ones are dropped
        val image = reader.acquireLatestImage() ?: return
        try {
            if (captureNextFrame) {
                captureNextFrame = false
                pendingFrame = image.toRawFrame()
            }
            viewfinder.obtain()?.let { buffer ->
                image.copyRawTo(buffer)
                viewfinder.publish(buffer)
            }
        } finally {
            image.close()
        }
        finishCaptureIfReady()
    }

    private fun finishCaptureIfReady() {
        val frame = pendingFrame ?: return
        val result = recentResults.firstOrNull { it.get(CaptureResult.SENSOR_TIMESTAMP) == frame.timestampNs }
        if (result == null) {
            // Results arrive in frame order: a newer one without a match means this frame's metadata was lost
            val newest = recentResults.lastOrNull()?.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            if (newest > frame.timestampNs) failCapture("Frame metadata was lost; try again")
            return
        }
        pendingFrame = null
        saving = true
        val orientation = pendingOrientation
        saveExecutor.execute {
            val outcome = try {
                CaptureState.Saved(saver.save(characteristics, result, frame, orientation))
            } catch (e: Exception) {
                CaptureState.Failed("Could not save DNG: ${e.message}")
            }
            handler.post {
                saving = false
                capturing = false
                _captureState.value = outcome
            }
        }
    }

    private fun failCapture(message: String) {
        capturing = false
        captureNextFrame = false
        pendingFrame = null
        _captureState.value = CaptureState.Failed(message)
    }

    /** Back-camera formula from the CaptureRequest.JPEG_ORIENTATION docs, as an EXIF constant. */
    private fun exifOrientation(deviceDegrees: Int): Int {
        val device = if (deviceDegrees == OrientationEventListener.ORIENTATION_UNKNOWN) 0 else deviceDegrees
        val rounded = (device + 45) / 90 * 90
        return when ((specs.sensorOrientation + rounded) % 360) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun closeInternal() {
        generation++
        session?.close()
        session = null
        device?.close()
        device = null
        rawReader?.close()
        rawReader = null
        recentResults.clear()
        _stats.value = null
        // A capture whose frame never reached the saver can't complete any more
        if (capturing && !saving) failCapture("Capture interrupted")
        pendingFrame = null
    }
}
