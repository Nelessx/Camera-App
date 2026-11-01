package com.nelessx.cameraapp.camera

import java.nio.ByteBuffer

const val HISTOGRAM_BINS = 128

// Sample one 2x2 colour block in every 16x16 area: ~196k photosites on a 12.6 MP frame, plenty for
// the distribution while keeping the camera thread fast enough for every frame
private const val SAMPLE_STEP = 16

/** Distribution of raw photosite values per colour channel, before any processing. */
class RawHistogram(
    /** Counts per [channel][bin]; channel 0 red, 1 green, 2 blue. Bins split 0..whiteLevel evenly. */
    val bins: List<IntArray>,
    /** Fraction of each channel's sampled photosites at the white level, i.e. saturated. */
    val clipped: List<Float>,
)

/**
 * Builds a histogram from a tightly packed RAW16 frame.
 *
 * @param cfa colour of each 2x2 position as in [CameraSpecs.cfaColours]
 */
fun rawHistogram(frame: ByteBuffer, width: Int, height: Int, cfa: IntArray, whiteLevel: Int): RawHistogram {
    val pixels = frame.asShortBuffer()
    val bins = List(3) { IntArray(HISTOGRAM_BINS) }
    val clipped = IntArray(3)
    val totals = IntArray(3)
    for (y in 0 until height - 1 step SAMPLE_STEP) {
        for (x in 0 until width - 1 step SAMPLE_STEP) {
            for (i in 0 until 4) {
                val channel = cfa[i]
                if (channel > 2) continue
                val value = pixels.get((y + i / 2) * width + x + i % 2).toInt() and 0xFFFF
                bins[channel][minOf(value * HISTOGRAM_BINS / (whiteLevel + 1), HISTOGRAM_BINS - 1)]++
                totals[channel]++
                if (value >= whiteLevel) clipped[channel]++
            }
        }
    }
    return RawHistogram(bins, List(3) { if (totals[it] > 0) clipped[it].toFloat() / totals[it] else 0f })
}
