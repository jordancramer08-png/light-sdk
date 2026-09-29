package com.thelightphone.reader.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The two cover pictures kept in a book's cache folder (CLAUDE.md 6): a small one for the
 * Library rows and a larger one for Book Details. Sizes are pixels on the 1080 × 1240 screen.
 */
enum class CoverSize(val fileName: String, val widthPx: Int, val heightPx: Int) {
    /** Drawn about 150 × 225 px at the left of a Library row. */
    SMALL("cover-small.png", 150, 225),

    /** Drawn up to about 480 × 720 px at the top of Book Details. */
    LARGE("cover-large.png", 480, 720),
}

/**
 * Turns a cover's bytes (JPEG, PNG, … as found in the EPUB) into the two PNGs. Uses
 * Android's BitmapFactory, so it only runs on the phone; LibraryStore takes it as a
 * parameter so its tests can use a stand-in.
 */
object CoverImages {

    /**
     * Writes the PNGs of [sizes] (both, for a book) into [folder]. False if the bytes aren't
     * a picture Android can read.
     */
    fun save(bytes: ByteArray, folder: File, sizes: List<CoverSize> = CoverSize.entries): Boolean {
        val largest = sizes.maxByOrNull { it.widthPx } ?: return false
        val decoded = decodeAtLeast(bytes, largest) ?: return false
        try {
            for (size in sizes) {
                val scaled = scaledFor(decoded, size)
                try {
                    File(folder, size.fileName).outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally {
                    if (scaled !== decoded) scaled.recycle()
                }
            }
        } finally {
            decoded.recycle()
        }
        return true
    }

    /** Only the small cover: a comic's first page, for its row in the comics list. */
    fun saveSmall(bytes: ByteArray, folder: File): Boolean = save(bytes, folder, listOf(CoverSize.SMALL))

    /**
     * Decodes the picture at a fraction of its size (inSampleSize, a power of 2) that is still
     * at least as big as [size], so a huge cover never fills the phone's memory.
     */
    private fun decodeAtLeast(bytes: ByteArray, size: CoverSize): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, size.widthPx, size.heightPx)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /**
     * The large cover fits inside its box; the small one fills its box (the Library row crops
     * the overflow), so both look sharp. Never scaled up.
     */
    private fun scaledFor(bitmap: Bitmap, size: CoverSize): Bitmap {
        val widthRatio = size.widthPx.toFloat() / bitmap.width
        val heightRatio = size.heightPx.toFloat() / bitmap.height
        val ratio = if (size == CoverSize.SMALL) max(widthRatio, heightRatio) else min(widthRatio, heightRatio)
        if (ratio >= 1f) return bitmap
        val width = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}

/**
 * The largest power of 2 that shrinks a [width] × [height] picture while keeping it at least
 * [minWidth] wide and [minHeight] tall (1 when it's already small).
 */
fun sampleSize(width: Int, height: Int, minWidth: Int, minHeight: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= minWidth && height / (sample * 2) >= minHeight) sample *= 2
    return sample
}
