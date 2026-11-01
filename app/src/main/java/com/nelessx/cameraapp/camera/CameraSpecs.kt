package com.nelessx.cameraapp.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Size
import android.view.SurfaceHolder
import kotlin.math.abs

/** Static facts about a camera, read once from its CameraCharacteristics. */
data class CameraSpecs(
    val id: String,
    val hardwareLevel: String,
    /** Largest RAW_SENSOR output, or null if the camera lacks the RAW capability. */
    val rawSize: Size?,
    val previewSize: Size,
)

// Preview only needs to look sharp on screen; larger sizes waste ISP bandwidth
private const val MAX_PREVIEW_WIDTH = 1920
private const val MAX_PREVIEW_HEIGHT = 1440

/** The first back-facing camera in the id list is the device's primary (main) camera. */
fun findMainBackCamera(manager: CameraManager): CameraSpecs? {
    val id = manager.cameraIdList.firstOrNull {
        manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
            CameraMetadata.LENS_FACING_BACK
    } ?: return null

    val chars = manager.getCameraCharacteristics(id)
    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
    val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
    val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    val sensorAspect = activeArray?.let { it.width().toFloat() / it.height() } ?: (4f / 3f)

    val rawSize = if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities) {
        map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
    } else {
        null
    }

    return CameraSpecs(
        id = id,
        hardwareLevel = hardwareLevelName(chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)),
        rawSize = rawSize,
        previewSize = choosePreviewSize(map.getOutputSizes(SurfaceHolder::class.java), sensorAspect),
    )
}

/** Largest size within the preview bounds that matches the sensor's aspect ratio, so nothing is cropped. */
private fun choosePreviewSize(sizes: Array<Size>, aspect: Float): Size {
    val fitting = sizes.filter { it.width <= MAX_PREVIEW_WIDTH && it.height <= MAX_PREVIEW_HEIGHT }
    return fitting.filter { abs(it.width.toFloat() / it.height - aspect) < 0.01f }.maxByOrNull { it.width * it.height }
        ?: fitting.maxByOrNull { it.width * it.height }
        ?: sizes.first()
}

private fun hardwareLevelName(level: Int?): String = when (level) {
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
    else -> "UNKNOWN"
}
