package com.nelessx.cameraapp.video

import android.graphics.Rect
import android.util.Size

/**
 * What a raw video records. The camera always delivers full 4096x3072 RAW frames at up to 30 fps;
 * storage sustains roughly 170 MB/s, so either the frame rate or the recorded area has to give.
 */
enum class VideoMode(
    val label: String,
    private val windowWidth: Int,
    private val windowHeight: Int,
    val targetFps: Int,
) {
    /** The whole sensor, no crop: 25 MB per frame, so 5 fps (~126 MB/s). */
    FULL_FRAME("Full · 5 fps", 0, 0, 5),

    /** A 1920x1080 window of photosites from the sensor centre, not scaled: 4 MB per frame at 30 fps. */
    WINDOW_1080("1080 · 30 fps", 1920, 1080, 30);

    /** Area of the RAW frame that gets recorded. Even offsets keep the colour filter pattern unchanged. */
    fun region(raw: Size): Rect {
        if (windowWidth == 0) return Rect(0, 0, raw.width, raw.height)
        val left = (raw.width - windowWidth) / 2 and 1.inv()
        val top = (raw.height - windowHeight) / 2 and 1.inv()
        return Rect(left, top, left + windowWidth, top + windowHeight)
    }
}
