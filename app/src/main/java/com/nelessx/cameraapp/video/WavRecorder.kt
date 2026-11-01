package com.nelessx.cameraapp.video

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

private const val SAMPLE_RATE = 48_000

/**
 * Records 16-bit PCM from the microphone into a WAV file. Uses the UNPROCESSED source (no gain
 * control, noise suppression or echo cancellation) when the device supports it.
 */
class WavRecorder(context: Context, private val file: File) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var output: RandomAccessFile? = null

    @Volatile
    private var running = false
    private var channels = 2
    private var dataBytes = 0L
    private var startedAtNs = 0L

    /** Estimated time of the first sample on CLOCK_BOOTTIME, the clock camera frame timestamps use. */
    @Volatile
    var firstSampleTimeNs = 0L
        private set

    var sourceName = ""
        private set

    /** Starts recording; false if no microphone could be opened. */
    // Only called once RECORD_AUDIO has been granted
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.CAMCORDER
        sourceName = if (unprocessed) "UNPROCESSED" else "CAMCORDER"
        val audio = createRecord(source, AudioFormat.CHANNEL_IN_STEREO, 2)
            ?: createRecord(source, AudioFormat.CHANNEL_IN_MONO, 1)
            ?: return false
        record = audio

        val out = RandomAccessFile(file, "rw").apply {
            setLength(0)
            write(ByteArray(44)) // header is written once the data size is known
        }
        output = out
        val chunk = ByteArray(SAMPLE_RATE * channels * 2 / 10) // 100 ms
        val stamp = AudioTimestamp()
        audio.startRecording()
        startedAtNs = SystemClock.elapsedRealtimeNanos()
        running = true
        worker = thread(name = "WavRecorder") {
            while (running) {
                val n = audio.read(chunk, 0, chunk.size)
                if (n > 0) {
                    out.write(chunk, 0, n)
                    dataBytes += n
                }
                if (firstSampleTimeNs == 0L &&
                    audio.getTimestamp(stamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS
                ) {
                    firstSampleTimeNs = stamp.nanoTime - stamp.framePosition * 1_000_000_000L / SAMPLE_RATE
                }
            }
        }
        return true
    }

    /** Stops recording and finalises the WAV header. */
    fun stop() {
        running = false
        worker?.join()
        record?.run {
            stop()
            release()
        }
        if (firstSampleTimeNs == 0L) firstSampleTimeNs = startedAtNs
        output?.use { out ->
            out.seek(0)
            out.write(wavHeader())
        }
    }

    private fun createRecord(source: Int, channelMask: Int, channelCount: Int): AudioRecord? {
        val minSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minSize <= 0) return null
        val audio = try {
            AudioRecord(source, SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT, maxOf(minSize, SAMPLE_RATE * channelCount))
        } catch (e: Exception) {
            return null
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            return null
        }
        channels = channelCount
        return audio
    }

    private fun wavHeader(): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray())
        putInt((36 + dataBytes).toInt())
        put("WAVE".toByteArray())
        put("fmt ".toByteArray())
        putInt(16)
        putShort(1) // PCM
        putShort(channels.toShort())
        putInt(SAMPLE_RATE)
        putInt(SAMPLE_RATE * channels * 2)
        putShort((channels * 2).toShort())
        putShort(16)
        put("data".toByteArray())
        putInt(dataBytes.toInt())
    }.array()
}
