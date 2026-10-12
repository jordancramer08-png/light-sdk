package com.jordan.watch.logic

import java.util.zip.CRC32
import kotlin.math.max
import kotlin.math.roundToInt

/** Sizes and names for the poster cache (the decoding itself is in storage/PosterCache). */
object PosterMath {

    /**
     * The largest power of two that, dividing [width], still leaves at least [targetPx].
     * 2000 px wide for 240 -> 8 (250 px).
     */
    fun sampleSize(width: Int, targetPx: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= targetPx) sample *= 2
        return sample
    }

    /** [width] x [height] scaled to [targetPx] wide, keeping its shape. Never scales up. */
    fun scaledSize(width: Int, height: Int, targetPx: Int): Pair<Int, Int> {
        if (width <= targetPx) return width to height
        val ratio = targetPx.toFloat() / width
        return targetPx to max(1, (height * ratio).roundToInt())
    }

    /** A file name for the cached copy; changes whenever the picture file changes. */
    fun cacheName(path: String, bytes: Long, modified: Long, px: Int): String {
        val text = "$path|$bytes|$modified"
        val crc = CRC32().apply { update(text.toByteArray()) }
        return "%08x%08x-%d".format(crc.value, text.hashCode(), px)
    }
}
