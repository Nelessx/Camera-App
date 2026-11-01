package com.nelessx.cameraapp.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** RAW16 pixels copied out of a camera Image, with no row padding, so the Image can be closed right away. */
class RawFrame(val pixels: ByteBuffer, val size: Size, val timestampNs: Long)

/** Copies a RAW_SENSOR image (16 bits per pixel) into a tightly packed buffer, as DngCreator.writeByteBuffer needs. */
fun Image.toRawFrame(): RawFrame {
    val plane = planes[0]
    val rowBytes = width * 2
    val src = plane.buffer
    val dst = ByteBuffer.allocateDirect(rowBytes * height).order(ByteOrder.nativeOrder())
    if (plane.rowStride == rowBytes) {
        dst.put(src)
    } else {
        // Skip the padding the camera adds at the end of each row
        for (row in 0 until height) {
            val start = row * plane.rowStride
            src.limit(start + rowBytes)
            src.position(start)
            dst.put(src)
        }
    }
    dst.rewind()
    return RawFrame(dst, Size(width, height), timestamp)
}

private const val ALBUM = "CameraApp"
private const val DNG_MIME = "image/x-adobe-dng"

/** Writes RAW frames as DNG files into DCIM/CameraApp. */
class DngSaver(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Writes [frame] with the metadata from [characteristics] and [result] (colour matrices, black level,
     * lens shading map, ...) and returns where it was saved.
     *
     * @param exifOrientation one of the android.media.ExifInterface ORIENTATION_* constants
     */
    fun save(
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        frame: RawFrame,
        exifOrientation: Int,
    ): String {
        val name = "RAW_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".dng"
        DngCreator(characteristics, result).use { dng ->
            dng.setOrientation(exifOrientation)
            val write: (OutputStream) -> Unit = { out ->
                BufferedOutputStream(out, 1 shl 20).use { dng.writeByteBuffer(it, frame.size, frame.pixels, 0) }
            }
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeToMediaStore(name, write)
            } else {
                writeToAppFolder(name, write)
            }
        }
    }

    private fun writeToMediaStore(name: String, write: (OutputStream) -> Unit): String {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, DNG_MIME)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$ALBUM")
            // Hidden from other apps until the file is complete
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create $name")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Could not open $name")
            write(out)
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return "${Environment.DIRECTORY_DCIM}/$ALBUM/$name"
    }

    // Android 9 can't write to shared storage without a permission, so keep files in the app's own folder
    private fun writeToAppFolder(name: String, write: (OutputStream) -> Unit): String {
        val dir = File(appContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES), ALBUM).apply { mkdirs() }
        val file = File(dir, name)
        write(FileOutputStream(file))
        return file.absolutePath
    }
}
