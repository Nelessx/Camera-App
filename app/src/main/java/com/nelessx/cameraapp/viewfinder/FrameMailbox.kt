package com.nelessx.cameraapp.viewfinder

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hands the newest RAW frame (16 bits per photosite, rows tightly packed) from the camera thread
 * to the GL thread without either of them waiting.
 *
 * Two buffers are enough: while the GL thread uploads one, the camera fills the other. A frame the
 * GL thread hasn't picked up yet is simply overwritten by a newer one.
 */
class FrameMailbox(val width: Int, val height: Int) {

    private val lock = Any()
    private val free = ArrayDeque<ByteBuffer>()
    private var latest: ByteBuffer? = null

    /** Called on the camera thread after each [publish]; the viewfinder uses it to request a redraw. */
    @Volatile
    var onFrameAvailable: (() -> Unit)? = null

    init {
        repeat(2) { free.addLast(ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.nativeOrder())) }
    }

    /** Camera thread: a buffer to fill with the next frame. */
    fun obtain(): ByteBuffer? = synchronized(lock) {
        free.removeFirstOrNull() ?: latest.also { latest = null }
    }

    /** Camera thread: makes a filled buffer the newest frame. */
    fun publish(buffer: ByteBuffer) {
        synchronized(lock) {
            latest?.let { free.addLast(it) }
            latest = buffer
        }
        onFrameAvailable?.invoke()
    }

    /** GL thread: takes the newest frame, if any. Hand it back with [recycle] once uploaded. */
    fun take(): ByteBuffer? = synchronized(lock) {
        latest.also { latest = null }
    }

    fun recycle(buffer: ByteBuffer) {
        synchronized(lock) { free.addLast(buffer) }
    }
}
