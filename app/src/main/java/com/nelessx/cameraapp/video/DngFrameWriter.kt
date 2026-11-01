package com.nelessx.cameraapp.video

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.os.Build
import android.util.Rational
import com.nelessx.cameraapp.camera.CameraSpecs
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/** Camera properties that every frame's DNG repeats; read once per recording. */
class DngStaticMetadata(chars: CameraCharacteristics, specs: CameraSpecs) {
    val make: String = Build.MANUFACTURER
    val model: String = Build.MODEL
    val cfaPattern = IntArray(4) { specs.cfaColours[it] }
    val blackLevels = specs.blackLevels
    val whiteLevel = specs.whiteLevel
    val colorMatrix1 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.toPairs()
    val colorMatrix2 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.toPairs()
    val forwardMatrix1 = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.toPairs()
    val forwardMatrix2 = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.toPairs()
    val calibration1 = chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)?.toPairs()
    val calibration2 = chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)?.toPairs()
    // Camera2 reference illuminants use the EXIF LightSource numbering, as DNG does
    val illuminant1 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)
    val illuminant2 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()
}

/** Per-frame values, from the frame's own CaptureResult. */
data class FrameMetadata(
    val timestampNs: Long,
    val exposureNs: Long,
    val iso: Int,
    val neutral: List<Rational>?,
    val aperture: Float,
    val focalLength: Float,
) {
    companion object {
        fun from(result: CaptureResult) = FrameMetadata(
            timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L,
            exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
            iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
            neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.toList(),
            aperture = result.get(CaptureResult.LENS_APERTURE) ?: 0f,
            focalLength = result.get(CaptureResult.LENS_FOCAL_LENGTH) ?: 0f,
        )
    }
}

/**
 * TIFF/DNG header for one uncompressed 16-bit colour filter array frame; the pixel data follows it.
 * Uses the same colour metadata as Android's DngCreator, plus the CinemaDNG FrameRate tag, and
 * supports frames smaller than the sensor (DngCreator doesn't).
 */
fun dngHeader(
    meta: DngStaticMetadata,
    frame: FrameMetadata,
    width: Int,
    height: Int,
    exifOrientation: Int,
    fps: Int,
): ByteBuffer = TiffIfd().apply {
    longs(254, 0) // NewSubFileType: main image
    longs(256, width.toLong())
    longs(257, height.toLong())
    shorts(258, 16) // BitsPerSample
    shorts(259, 1) // Compression: none
    shorts(262, 32803) // PhotometricInterpretation: colour filter array
    ascii(271, meta.make)
    ascii(272, meta.model)
    longs(TAG_STRIP_OFFSETS, 0) // filled in by build()
    shorts(274, exifOrientation)
    shorts(277, 1) // SamplesPerPixel
    longs(278, height.toLong()) // RowsPerStrip: one strip
    longs(279, width.toLong() * height * 2) // StripByteCounts
    shorts(284, 1) // PlanarConfiguration
    ascii(305, "CameraApp")
    ascii(306, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date()))
    shorts(33421, 2, 2) // CFARepeatPatternDim
    bytes(33422, *meta.cfaPattern) // CFAPattern: 0 red, 1 green, 2 blue
    rationals(33434, frame.exposureNs, 1_000_000_000L) // ExposureTime
    rationals(33437, (frame.aperture * 100).roundToLong(), 100) // FNumber
    shorts(34855, frame.iso) // ISOSpeedRatings
    rationals(37386, (frame.focalLength * 1000).roundToLong(), 1000) // FocalLength
    bytes(50706, 1, 4, 0, 0) // DNGVersion
    bytes(50707, 1, 1, 0, 0) // DNGBackwardVersion
    ascii(50708, "${meta.model}-${meta.make}") // UniqueCameraModel
    bytes(50710, 0, 1, 2) // CFAPlaneColor
    shorts(50711, 1) // CFALayout: rectangular
    shorts(50713, 2, 2) // BlackLevelRepeatDim
    longs(50714, *meta.blackLevels.map { it.toLong() }.toLongArray())
    longs(50717, meta.whiteLevel.toLong())
    longs(50719, 0, 0) // DefaultCropOrigin: no crop
    longs(50720, width.toLong(), height.toLong()) // DefaultCropSize: whole frame
    meta.colorMatrix1?.let { srationals(50721, it) }
    meta.colorMatrix2?.let { srationals(50722, it) }
    meta.calibration1?.let { srationals(50723, it) }
    meta.calibration2?.let { srationals(50724, it) }
    // White balance is only a hint for the editor; skipped if the camera reported none
    frame.neutral?.takeIf { n -> n.size == 3 && n.all { it.numerator > 0 && it.denominator > 0 } }?.let { n ->
        rationals(50728, *n.flatMap { listOf(it.numerator.toLong(), it.denominator.toLong()) }.toLongArray())
    }
    meta.illuminant1?.let { shorts(50778, it) }
    meta.illuminant2?.let { shorts(50779, it) }
    meta.forwardMatrix1?.let { srationals(50964, it) }
    meta.forwardMatrix2?.let { srationals(50965, it) }
    srationals(51044, intArrayOf(fps, 1)) // CinemaDNG FrameRate
}.build()

/** Writes [header] followed by [pixels] (position 0, limit = pixel bytes) to [file]. */
fun writeDngFrame(file: File, header: ByteBuffer, pixels: ByteBuffer) {
    FileOutputStream(file).channel.use { channel ->
        val parts = arrayOf(header, pixels)
        while (parts.any { it.hasRemaining() }) channel.write(parts)
    }
}

private const val TAG_STRIP_OFFSETS = 273
private const val TYPE_BYTE = 1
private const val TYPE_ASCII = 2
private const val TYPE_SHORT = 3
private const val TYPE_LONG = 4
private const val TYPE_RATIONAL = 5
private const val TYPE_SRATIONAL = 10

private fun ColorSpaceTransform.toPairs(): IntArray = IntArray(18).also { copyElements(it, 0) }

/** A single little-endian TIFF directory, laid out by [build]. */
private class TiffIfd {

    private class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray)

    private val entries = mutableListOf<Entry>()

    fun bytes(tag: Int, vararg values: Int) =
        add(tag, TYPE_BYTE, values.size, ByteArray(values.size) { values[it].toByte() })

    fun ascii(tag: Int, text: String) {
        val data = (text + "\u0000").toByteArray(Charsets.US_ASCII)
        add(tag, TYPE_ASCII, data.size, data)
    }

    fun shorts(tag: Int, vararg values: Int) =
        add(tag, TYPE_SHORT, values.size, littleEndian(values.size * 2) { b -> values.forEach { b.putShort(it.toShort()) } })

    fun longs(tag: Int, vararg values: Long) =
        add(tag, TYPE_LONG, values.size, littleEndian(values.size * 4) { b -> values.forEach { b.putInt(it.toInt()) } })

    /** Unsigned fractions, given as numerator, denominator pairs. */
    fun rationals(tag: Int, vararg pairs: Long) =
        add(tag, TYPE_RATIONAL, pairs.size / 2, littleEndian(pairs.size * 4) { b -> pairs.forEach { b.putInt(it.toInt()) } })

    /** Signed fractions, given as numerator, denominator pairs. */
    fun srationals(tag: Int, pairs: IntArray) =
        add(tag, TYPE_SRATIONAL, pairs.size / 2, littleEndian(pairs.size * 4) { b -> pairs.forEach { b.putInt(it) } })

    private fun add(tag: Int, type: Int, count: Int, data: ByteArray) {
        entries += Entry(tag, type, count, data)
    }

    private inline fun littleEndian(size: Int, fill: (ByteBuffer) -> Unit): ByteArray =
        ByteArray(size).also { fill(ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)) }

    /** Header, directory, then values too big to fit in their entry; StripOffsets points just past it all. */
    fun build(): ByteBuffer {
        entries.sortBy { it.tag } // TIFF requires ascending tags
        val directorySize = 2 + entries.size * 12 + 4
        val outOfLine = entries.sumOf { if (it.data.size > 4) align(it.data.size, 2) else 0 }
        val headerSize = align(8 + directorySize + outOfLine, 4)
        ByteBuffer.wrap(entries.first { it.tag == TAG_STRIP_OFFSETS }.data)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0, headerSize)

        val buf = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        buf.putShort(entries.size.toShort())
        var dataOffset = 8 + directorySize
        for (e in entries) {
            buf.putShort(e.tag.toShort()).putShort(e.type.toShort()).putInt(e.count)
            if (e.data.size <= 4) {
                buf.put(e.data)
                repeat(4 - e.data.size) { buf.put(0) }
            } else {
                buf.putInt(dataOffset)
                dataOffset += align(e.data.size, 2)
            }
        }
        buf.putInt(0) // no further directories
        for (e in entries) {
            if (e.data.size > 4) {
                buf.put(e.data)
                if (e.data.size % 2 == 1) buf.put(0)
            }
        }
        buf.rewind()
        return buf
    }

    private fun align(value: Int, to: Int) = (value + to - 1) / to * to
}
