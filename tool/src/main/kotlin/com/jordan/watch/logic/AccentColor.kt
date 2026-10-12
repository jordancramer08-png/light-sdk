package com.jordan.watch.logic

/**
 * The detail page's accent, taken from the poster: the most common strong, mid-bright hue.
 * Pixels are ARGB ints. Grey, very dark and very light pixels are skipped (they'd give a
 * muddy accent); the winning hue's pixels are averaged and brightened so the color reads on
 * the dark theme. Null when the poster has no real color (black-and-white posters).
 */
object AccentColor {

    private const val BUCKETS = 24
    private const val MIN_SATURATION = 0.25f
    private const val MIN_VALUE = 0.2f
    private const val MAX_VALUE = 0.97f

    /** At least this share of the sampled pixels must be colorful. */
    private const val MIN_SHARE = 0.03f

    fun pick(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null
        val count = IntArray(BUCKETS)
        val r = LongArray(BUCKETS)
        val g = LongArray(BUCKETS)
        val b = LongArray(BUCKETS)
        for (p in pixels) {
            val pr = (p shr 16) and 0xFF
            val pg = (p shr 8) and 0xFF
            val pb = p and 0xFF
            val max = maxOf(pr, pg, pb)
            val min = minOf(pr, pg, pb)
            val value = max / 255f
            val saturation = if (max == 0) 0f else (max - min).toFloat() / max
            if (saturation < MIN_SATURATION || value < MIN_VALUE || value > MAX_VALUE) continue
            val bucket = (hue(pr, pg, pb, max, min) / 360f * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
            count[bucket]++
            r[bucket] += pr.toLong()
            g[bucket] += pg.toLong()
            b[bucket] += pb.toLong()
        }
        val best = count.indices.maxByOrNull { count[it] } ?: return null
        if (count[best] < pixels.size * MIN_SHARE || count[best] == 0) return null
        val n = count[best]
        return brighten((r[best] / n).toInt(), (g[best] / n).toInt(), (b[best] / n).toInt())
    }

    /** 0..360. */
    private fun hue(r: Int, g: Int, b: Int, max: Int, min: Int): Float {
        val d = (max - min).toFloat()
        if (d == 0f) return 0f
        val h = when (max) {
            r -> ((g - b) / d).mod(6f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return h * 60f
    }

    /** Scales the color so its brightest channel is at least 200 (readable on black). */
    private fun brighten(r: Int, g: Int, b: Int): Int {
        val max = maxOf(r, g, b).coerceAtLeast(1)
        val scale = if (max < 200) 200f / max else 1f
        fun c(v: Int) = (v * scale).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }
}
