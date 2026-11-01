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
import android.hardware.camera2.CaptureFailure
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
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** What the user has chosen; applied to every preview and capture request. */
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
)

sealed interface CaptureState {
    data object Idle : CaptureState
    data object Capturing : CaptureState
    data class Saved(val location: String) : CaptureState
    data class Failed(val message: String) : CaptureState
}

// The UI only needs a few updates per second, not one per frame
private const val STATS_INTERVAL_NS = 100_000_000L

/**
 * Owns one Camera2 device: the preview stream, the RAW stream, and DNG capture.
 *
 * All camera state lives on a dedicated background thread; the public methods can be called
 * from any thread and just post work to it. DNG files are written on a separate thread so slow
 * storage never stalls camera callbacks.
 */
class CameraController(context: Context, private val specs: CameraSpecs) {

    private val manager = context.getSystemService(CameraManager::class.java)
    private val characteristics = manager.getCameraCharacteristics(specs.id)
    private val saver = DngSaver(context)

    private val thread = HandlerThread("CameraThread").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val saveExecutor = Executors.newSingleThreadExecutor()

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var rawReader: ImageReader? = null

    // Bumped on every open/close so callbacks belonging to an earlier open() are ignored
    private var generation = 0
    private var lastStatsTimeNs = 0L

    @Volatile
    private var settings = CaptureSettings()
    private val settingsUpdateQueued = AtomicBoolean(false)

    // Only one RAW capture is in flight at a time; its image and metadata arrive separately
    private var capturing = false
    private var saving = false
    private var pendingResult: TotalCaptureResult? = null
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

    /** Opens the camera and streams preview frames into [previewSurface]. */
    // Only called once CameraScreen has confirmed the CAMERA permission
    @SuppressLint("MissingPermission")
    fun open(previewSurface: Surface) {
        handler.post {
            closeInternal()
            val gen = generation
            this.previewSurface = previewSurface
            _error.value = null
            try {
                manager.openCamera(specs.id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (gen != generation) {
                            camera.close()
                            return
                        }
                        device = camera
                        startSession(camera, previewSurface, gen)
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

    /** Applies new settings to the live preview; the next capture uses them too. */
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

    /** Captures one RAW frame with the current settings and saves it as a DNG. */
    fun capture() {
        handler.post {
            val activeSession = session
            val reader = rawReader
            if (activeSession == null || reader == null) {
                _captureState.value = CaptureState.Failed("Camera not ready")
                return@post
            }
            if (capturing) return@post
            capturing = true
            pendingResult = null
            pendingFrame = null
            pendingOrientation = exifOrientation(deviceOrientation)
            _captureState.value = CaptureState.Capturing

            val gen = generation
            try {
                // RAW only: this frame has shading correction off, which would flash a seam in the preview
                val request = activeSession.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    applySettings(settings, forRaw = true)
                    // DngCreator embeds this map so raw editors know the lens's vignetting
                    set(
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                        CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON,
                    )
                }.build()
                activeSession.capture(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        if (gen != generation) return
                        pendingResult = result
                        finishCaptureIfReady()
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure,
                    ) {
                        if (gen == generation) failCapture("Capture failed (reason ${failure.reason})")
                    }
                }, handler)
            } catch (e: CameraAccessException) {
                failCapture("Capture failed: ${e.message}")
            } catch (e: IllegalStateException) {
                failCapture("Camera was closed")
            }
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

    private fun startSession(camera: CameraDevice, surface: Surface, gen: Int) {
        val reader = specs.rawSize?.let { size ->
            ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2).apply {
                setOnImageAvailableListener({ onRawImage(it) }, handler)
            }
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
        val outputs = listOfNotNull(OutputConfiguration(surface), reader?.let { OutputConfiguration(it.surface) })
        try {
            camera.createCaptureSession(
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback)
            )
        } catch (e: CameraAccessException) {
            _error.value = "Cannot create session: ${e.message}"
        }
    }

    /** (Re)starts the preview with the current settings. Runs on the camera thread. */
    private fun startRepeating() {
        val activeSession = session ?: return
        val surface = previewSurface ?: return
        try {
            val request = activeSession.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                applySettings(settings, forRaw = false)
            }.build()
            activeSession.setRepeatingRequest(request, previewCallback, handler)
        } catch (e: CameraAccessException) {
            _error.value = "Cannot start preview: ${e.message}"
        } catch (e: IllegalStateException) {
            // The session was closed in the meantime; nothing to do
        }
    }

    /**
     * @param forRaw true for the request that produces the DNG. Shading correction is only switched off
     * there: with it off, this phone's ISP processes the two frame halves inconsistently and the preview
     * shows a flickering seam down the middle. RAW pixels are unaffected either way.
     */
    private fun CaptureRequest.Builder.applySettings(s: CaptureSettings, forRaw: Boolean) {
        // Switch off every ISP stage this camera allows. Most only touch processed output (the preview),
        // but some devices apply hot pixel or shading correction to RAW too.
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
        if (forRaw) {
            setOffIfAvailable(
                CaptureRequest.SHADING_MODE,
                CameraCharacteristics.SHADING_AVAILABLE_MODES,
                CameraMetadata.SHADING_MODE_OFF,
            )
        }
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

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
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
                focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
            )
        }
    }

    private fun onRawImage(reader: ImageReader) {
        val image = reader.acquireNextImage() ?: return
        try {
            // Copy now so the reader's buffer is free again; the image itself dies with the session
            if (capturing && pendingFrame == null) pendingFrame = image.toRawFrame()
        } finally {
            image.close()
        }
        finishCaptureIfReady()
    }

    private fun finishCaptureIfReady() {
        val result = pendingResult ?: return
        val frame = pendingFrame ?: return
        pendingResult = null
        pendingFrame = null
        if (result.get(CaptureResult.SENSOR_TIMESTAMP) != frame.timestampNs) {
            failCapture("RAW frame didn't match its metadata")
            return
        }
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
        pendingResult = null
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
        _stats.value = null
        // A capture whose frame never reached the saver can't complete any more
        if (capturing && !saving) failCapture("Capture interrupted")
        pendingResult = null
        pendingFrame = null
    }
}
