package com.nelessx.cameraapp.video

import android.content.Context
import android.graphics.Rect
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Progress of a recording, for the UI. */
data class RecordingStats(
    /** Where the recording is saved, relative to shared storage. */
    val folder: String,
    val frames: Int = 0,
    /** Frames skipped because storage couldn't keep up. */
    val dropped: Int = 0,
    val bytes: Long = 0,
    val durationNs: Long = 0,
    /** Stop was requested; queued frames are still being written. */
    val stopping: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null,
)

// Off-heap memory for frames waiting to be written: a few seconds of slack for slow storage
private const val BUFFER_BUDGET_BYTES = 512L * 1024 * 1024

/**
 * Records RAW frames as a sequence of uncompressed DNGs (one file per frame, as CinemaDNG does),
 * plus a WAV file, a per-frame log and a summary, all in [folder].
 *
 * [obtainBuffer] and [submit] are called on the camera thread; frames are written by two background
 * threads, so file numbers follow capture order even if writes finish out of order.
 */
class RawVideoRecorder(
    context: Context,
    private val folder: File,
    displayFolder: String,
    private val mode: VideoMode,
    private val region: Rect,
    private val meta: DngStaticMetadata,
    private val exifOrientation: Int,
    recordAudio: Boolean,
    private val timestampSource: String,
    private val onStats: (RecordingStats) -> Unit,
) {
    private val frameBytes = region.width() * region.height() * 2
    private val pool = FrameBufferPool(frameBytes, maxOf(4, (BUFFER_BUDGET_BYTES / frameBytes).toInt()))
    private val writers = Executors.newFixedThreadPool(2)
    private val frameLog = folder.resolve("frames.csv").bufferedWriter().apply {
        appendLine("frame,sensor_timestamp_ns,exposure_ns,iso")
    }
    private val audio = if (recordAudio) {
        WavRecorder(context, folder.resolve("${folder.name}.wav")).takeIf { it.start() }
    } else {
        null
    }

    private var nextIndex = 0

    @Volatile
    private var firstTimestampNs = -1L
    private val lastTimestampNs = AtomicLong()
    private val written = AtomicInteger()
    private val dropped = AtomicInteger()
    private val bytes = AtomicLong()

    @Volatile
    private var stopping = false

    @Volatile
    private var finished = false

    @Volatile
    var error: String? = null
        private set

    private var stats = RecordingStats(displayFolder)

    init {
        publish()
    }

    /** A buffer for the next frame, or null when storage has fallen behind and this frame must be skipped. */
    fun obtainBuffer(): ByteBuffer? {
        val buffer = if (error == null) pool.obtain() else null
        if (buffer == null) {
            dropped.incrementAndGet()
            publish()
        }
        return buffer
    }

    /** Queues a filled buffer (position 0) as the next frame of the sequence. */
    fun submit(buffer: ByteBuffer, frame: FrameMetadata) {
        val index = nextIndex++
        if (firstTimestampNs < 0) firstTimestampNs = frame.timestampNs
        lastTimestampNs.set(frame.timestampNs)
        frameLog.appendLine("$index,${frame.timestampNs},${frame.exposureNs},${frame.iso}")
        writers.execute {
            try {
                val header = dngHeader(meta, frame, region.width(), region.height(), exifOrientation, mode.targetFps)
                buffer.position(0)
                buffer.limit(frameBytes)
                writeDngFrame(folder.resolve(String.format(Locale.US, "%s_%06d.dng", folder.name, index)), header, buffer)
                bytes.addAndGet(header.capacity().toLong() + frameBytes)
                written.incrementAndGet()
            } catch (e: IOException) {
                error = "Recording stopped: ${e.message}"
            } finally {
                pool.recycle(buffer)
                publish()
            }
        }
        publish()
    }

    /** Stops audio, finishes writing queued frames, then writes the summary. Returns immediately. */
    fun finish() {
        stopping = true
        publish()
        thread(name = "RecordingFinisher") {
            audio?.stop()
            writers.shutdown()
            writers.awaitTermination(2, TimeUnit.MINUTES)
            frameLog.close()
            writeSummary()
            pool.close()
            finished = true
            publish()
        }
    }

    @Synchronized
    private fun publish() {
        stats = stats.copy(
            frames = written.get(),
            dropped = dropped.get(),
            bytes = bytes.get(),
            durationNs = if (firstTimestampNs < 0) 0 else lastTimestampNs.get() - firstTimestampNs,
            stopping = stopping,
            finished = finished,
            error = error,
        )
        onStats(stats)
    }

    private fun writeSummary() {
        val lines = mutableListOf(
            "mode=${mode.name}",
            "frame_size=${region.width()}x${region.height()}",
            "sensor_region=${region.left},${region.top},${region.right},${region.bottom}",
            "target_fps=${mode.targetFps}",
            "frames=${written.get()}",
            "dropped_frames=${dropped.get()}",
            "first_frame_timestamp_ns=$firstTimestampNs",
            "last_frame_timestamp_ns=${lastTimestampNs.get()}",
            "timestamp_clock=$timestampSource",
        )
        val recorder = audio
        if (recorder == null) {
            lines += "audio=none"
        } else {
            lines += "audio=${folder.name}.wav"
            lines += "audio_source=${recorder.sourceName}"
            lines += "audio_first_sample_timestamp_ns=${recorder.firstSampleTimeNs}"
            if (firstTimestampNs >= 0) {
                // Positive: the WAV starts this long before the first frame, so trim that much to line them up
                lines += String.format(
                    Locale.US, "audio_starts_before_first_frame_ms=%.3f",
                    (firstTimestampNs - recorder.firstSampleTimeNs) / 1e6,
                )
            }
        }
        error?.let { lines += "error=$it" }
        try {
            folder.resolve("recording.txt").writeText(lines.joinToString("\n", postfix = "\n"))
        } catch (e: IOException) {
            error = error ?: "Could not write summary: ${e.message}"
        }
    }
}
