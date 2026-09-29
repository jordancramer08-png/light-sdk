package com.thelightphone.reader.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.thelightphone.reader.comics.MAX_PAGE_BYTES
import com.thelightphone.reader.comics.PANEL_GRID_LONG_SIDE
import com.thelightphone.reader.comics.PixelRect
import com.thelightphone.reader.comics.detectPanels
import com.thelightphone.reader.comics.fittedSize
import com.thelightphone.reader.comics.grayFromArgb
import com.thelightphone.reader.comics.shrinkToLongSide
import com.thelightphone.reader.comics.readComicEntry
import java.io.File
import java.io.IOException

/** A page decoded to fit the screen. [imageWidth] × [imageHeight] is the page's full size. */
class FittedPage(val bitmap: Bitmap, val imageWidth: Int, val imageHeight: Int)

/**
 * Reads comic pages out of a CBZ for the viewer (CLAUDE.md 12). Uses Android's BitmapFactory
 * and BitmapRegionDecoder, so it only runs on the phone. Every call reads the file: call it
 * off the main thread, one at a time.
 */
object ComicPageImages {

    /** The page's compressed bytes, or null when it's missing or too big. */
    fun readPage(file: File, entryName: String): ByteArray? = readComicEntry(file, entryName, MAX_PAGE_BYTES)

    /**
     * Decodes the page just big enough to fill the screen: shrunk by a power of 2 while
     * decoding (inSampleSize), so a huge page never fills the memory, then scaled to exactly
     * the fitted size. Null when the bytes aren't a picture Android can read.
     */
    fun decodeFitted(bytes: ByteArray, screenWidth: Int, screenHeight: Int): FittedPage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        val (fitWidth, fitHeight) = fittedSize(width, height, screenWidth, screenHeight)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(width, height, fitWidth, fitHeight) }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (decoded.width <= fitWidth) return FittedPage(decoded, width, height)
        val scaled = Bitmap.createScaledBitmap(decoded, fitWidth, fitHeight, true)
        if (scaled !== decoded) decoded.recycle()
        return FittedPage(scaled, width, height)
    }

    /** Opens the page for decoding pieces of it sharply; null for pictures it can't cut (GIF, BMP). */
    fun openRegions(bytes: ByteArray): BitmapRegionDecoder? =
        try {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        } catch (e: IOException) {
            null
        }

    /** The page's pixels in [region], shrunk by [sampleSize] (a power of 2). */
    fun decodeRegion(decoder: BitmapRegionDecoder, region: PixelRect, sampleSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return decoder.decodeRegion(Rect(region.left, region.top, region.right, region.bottom), options)
    }

    /**
     * The page's panels (CLAUDE.md 12): decoded small (at least [PANEL_GRID_LONG_SIDE] px on its
     * long side, shrunk by a power of 2), made gray, shrunk to that size, then looked for.
     * Null when the bytes aren't a picture Android can read.
     */
    fun findPanels(bytes: ByteArray): PagePanels? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        val (gridWidth, gridHeight) = fittedSize(width, height, PANEL_GRID_LONG_SIDE, PANEL_GRID_LONG_SIDE)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(width, height, gridWidth, gridHeight) }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val argb = IntArray(decoded.width * decoded.height)
        decoded.getPixels(argb, 0, decoded.width, 0, 0, decoded.width, decoded.height)
        val gray = shrinkToLongSide(grayFromArgb(argb, decoded.width, decoded.height))
        decoded.recycle()
        val panels = detectPanels(gray, width, height)
        return PagePanels(width, height, panels.map(PanelBox::of))
    }
}
