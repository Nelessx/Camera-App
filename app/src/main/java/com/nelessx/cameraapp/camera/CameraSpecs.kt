package com.nelessx.cameraapp.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Range
import android.util.Size

/** Static facts about a camera, read once from its CameraCharacteristics. */
data class CameraSpecs(
    val id: String,
    val hardwareLevel: String,
    /** Clockwise rotation of the sensor relative to the device's natural (portrait) orientation. */
    val sensorOrientation: Int,
    /** Largest RAW_SENSOR output, or null if the camera lacks the RAW capability. */
    val rawSize: Size?,
    /** SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_* value: which colour filter sits on each photosite. */
    val cfaArrangement: Int,
    /** Black level of each position in the 2x2 colour filter block, indexed row * 2 + column. */
    val blackLevels: List<Int>,
    /** Highest value a photosite can report (sensor saturation). */
    val whiteLevel: Int,
    /** Null when the camera has no MANUAL_SENSOR capability. */
    val isoRange: Range<Int>?,
    val exposureRangeNs: Range<Long>?,
    /** Closest focus in diopters (1/metres); 0 means fixed focus. */
    val minFocusDiopters: Float,
    /** Shortest frame duration of the full-size RAW stream. */
    val minFrameDurationNs: Long,
) {
    val supportsManual: Boolean get() = isoRange != null && exposureRangeNs != null
}

/** The first back-facing camera in the id list is the device's primary (main) camera. */
fun findMainBackCamera(manager: CameraManager): CameraSpecs? {
    val id = manager.cameraIdList.firstOrNull {
        manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
            CameraMetadata.LENS_FACING_BACK
    } ?: return null

    val chars = manager.getCameraCharacteristics(id)
    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
    val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)

    val rawSize = if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities) {
        map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
    } else {
        null
    }
    val manual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities
    val blackPattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)

    return CameraSpecs(
        id = id,
        hardwareLevel = hardwareLevelName(chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)),
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
        rawSize = rawSize,
        cfaArrangement = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB,
        blackLevels = List(4) { i -> blackPattern?.getOffsetForIndex(i % 2, i / 2) ?: 0 },
        whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023,
        isoRange = if (manual) chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) else null,
        exposureRangeNs = if (manual) chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) else null,
        minFocusDiopters = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
        minFrameDurationNs = rawSize?.let { map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, it) } ?: 0L,
    )
}

private fun hardwareLevelName(level: Int?): String = when (level) {
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
    else -> "UNKNOWN"
}
