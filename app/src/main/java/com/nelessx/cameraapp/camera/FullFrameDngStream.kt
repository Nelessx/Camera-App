package com.nelessx.cameraapp.camera

import android.util.Size
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val HEAD_SIZE = 64 * 1024
private const val TAG_DEFAULT_CROP_ORIGIN = 50719
private const val TAG_DEFAULT_CROP_SIZE = 50720
private const val TYPE_SHORT = 3
private const val TYPE_LONG = 4
private const val TYPE_RATIONAL = 5

/**
 * Passes a DNG through unchanged except for its default crop, which is widened to the whole image.
 *
 * DngCreator marks an 8-pixel border as "crop by default", so raw editors would hide it. All TIFF
 * metadata comes before the pixel data, so only the first [HEAD_SIZE] bytes are held back and patched.
 */
class FullFrameDngStream(private val out: OutputStream, private val size: Size) : OutputStream() {

    private val head = ByteArray(HEAD_SIZE)
    private var headLength = 0
    private var headWritten = false

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        var offset = off
        var length = len
        if (!headWritten) {
            val n = minOf(length, HEAD_SIZE - headLength)
            System.arraycopy(b, offset, head, headLength, n)
            headLength += n
            offset += n
            length -= n
            if (headLength == HEAD_SIZE) writeHead()
        }
        if (length > 0) out.write(b, offset, length)
    }

    override fun flush() {
        out.flush()
    }

    override fun close() {
        if (!headWritten) writeHead()
        out.close()
    }

    private fun writeHead() {
        clearDefaultCrop(head, headLength, size)
        out.write(head, 0, headLength)
        headWritten = true
    }
}

/** Sets DefaultCropOrigin to 0,0 and DefaultCropSize to the full image, where those values lie within [head]. */
private fun clearDefaultCrop(head: ByteArray, length: Int, size: Size) {
    if (length < 8) return
    val order = if (head[0] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
    val buf = ByteBuffer.wrap(head, 0, length).order(order)
    val ifd = buf.getInt(4)
    if (ifd < 0 || ifd + 2 > length) return

    val entryCount = buf.getShort(ifd).toInt() and 0xFFFF
    for (i in 0 until entryCount) {
        val entry = ifd + 2 + i * 12
        if (entry + 12 > length) return
        val values = when (buf.getShort(entry).toInt() and 0xFFFF) {
            TAG_DEFAULT_CROP_ORIGIN -> intArrayOf(0, 0)
            TAG_DEFAULT_CROP_SIZE -> intArrayOf(size.width, size.height)
            else -> continue
        }
        val type = buf.getShort(entry + 2).toInt() and 0xFFFF
        val unit = when (type) {
            TYPE_SHORT -> 2
            TYPE_LONG -> 4
            TYPE_RATIONAL -> 8
            else -> continue
        }
        if (buf.getInt(entry + 4) != values.size) continue
        // Values of 4 bytes or less live inside the entry itself; larger ones are elsewhere in the head
        val dataOffset = if (unit * values.size <= 4) entry + 8 else buf.getInt(entry + 8)
        if (dataOffset < 0 || dataOffset + unit * values.size > length) continue

        values.forEachIndexed { k, value ->
            val o = dataOffset + k * unit
            when (type) {
                TYPE_SHORT -> buf.putShort(o, value.toShort())
                TYPE_LONG -> buf.putInt(o, value)
                TYPE_RATIONAL -> {
                    buf.putInt(o, value)
                    buf.putInt(o + 4, 1)
                }
            }
        }
    }
}
