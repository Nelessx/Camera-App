package com.nelessx.cameraapp.video

import android.os.SharedMemory
import android.system.ErrnoException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reusable frame buffers outside the Java heap, which is capped at 256 MB (only ~10 full frames).
 * They let a recording ride out a few seconds of slow storage. Buffers are created on demand,
 * up to [maxBuffers]; [close] may only be called once nothing uses them any more.
 */
class FrameBufferPool(private val frameBytes: Int, private val maxBuffers: Int) : AutoCloseable {

    private val lock = Any()
    private val free = ArrayDeque<ByteBuffer>()
    private val allocated = mutableListOf<Pair<SharedMemory, ByteBuffer>>()

    /** A buffer of exactly frameBytes, or null if all are in use (storage has fallen behind). */
    fun obtain(): ByteBuffer? = synchronized(lock) {
        free.removeFirstOrNull() ?: if (allocated.size < maxBuffers) allocate() else null
    }

    fun recycle(buffer: ByteBuffer) {
        synchronized(lock) { free.addLast(buffer) }
    }

    private fun allocate(): ByteBuffer? = try {
        val memory = SharedMemory.create("raw-frame", frameBytes)
        val buffer = memory.mapReadWrite().order(ByteOrder.nativeOrder())
        allocated += memory to buffer
        buffer
    } catch (e: ErrnoException) {
        null
    }

    override fun close() {
        synchronized(lock) {
            allocated.forEach { (memory, buffer) ->
                SharedMemory.unmap(buffer)
                memory.close()
            }
            allocated.clear()
            free.clear()
        }
    }
}
