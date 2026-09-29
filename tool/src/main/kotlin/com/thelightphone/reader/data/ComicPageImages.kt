package com.thelightphone.reader.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import com.thelightphone.reader.comics.ColorImage
import com.thelightphone.reader.comics.PANEL_GRID_LONG_SIDE
import com.thelightphone.reader.comics.PageLayout
import com.thelightphone.reader.comics.PixelRect
import com.thelightphone.reader.comics.analysePage
import com.thelightphone.reader.comics.cropWithinPage
import com.thelightphone.reader.comics.fittedSize
import com.thelightphone.reader.comics.shrinkColorToLongSide
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A page decoded to fit the screen as [layout] lays it out (turned already when
 * [PageLayout.ROTATED]). [pageWidth] × [pageHeight] is the page's full size, not turned;
 * only its [crop] (in page pixels; the whole page when nothing is cropped) was decoded.
 */
class FittedPage(val bitmap: Bitmap, val pageWidth: Int, val pageHeight: Int, val crop: PixelRect, val layout: PageLayout)

/**
 * Reads comic pages out of a CBZ for the viewer (CLAUDE.md 12). Uses Android's BitmapFactory
 * and BitmapRegionDecoder, so it only runs on the phone. Decoding takes a moment: call it
 * off the main thread, one at a time.
 */
object ComicPageImages {

    /**
     * Decodes the page's [cropFor] part (it's given the page's size) just big enough to fill
     * the screen as [layoutFor] lays it out (given the crop's size): shrunk by a power of 2
     * while decoding (inSampleSize), so a huge page never fills the memory, then cut to the
     * crop and scaled to exactly the fitted size in one step, then turned for
     * [PageLayout.ROTATED]. Null when the bytes aren't a picture Android can read.
     */
    fun decodeFitted(
        bytes: ByteArray,
        screenWidth: Int,
        screenHeight: Int,
        cropFor: (Int, Int) -> PixelRect,
        layoutFor: (Int, Int) -> PageLayout,
    ): FittedPage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        val crop = cropWithinPage(cropFor(width, height), width, height)
        val layout = layoutFor(crop.width, crop.height)
        val rotated = layout == PageLayout.ROTATED
        // Fitted as shown (a turned page is as tall as the page is wide), then back to the page's own way round.
        val (shownWidth, shownHeight) = fittedSize(
            if (rotated) crop.height else crop.width,
            if (rotated) crop.width else crop.height,
            screenWidth,
            screenHeight,
            fitHeight = layout == PageLayout.FIT_HEIGHT,
        )
        val fitWidth = if (rotated) shownHeight else shownWidth
        val fitHeight = if (rotated) shownWidth else shownHeight
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(crop.width, crop.height, fitWidth, fitHeight) }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val fitted = cutAndScale(decoded, crop, width, height, fitWidth, fitHeight)
        if (fitted !== decoded) decoded.recycle()
        val bitmap = if (rotated) turnLeft(fitted) else fitted
        return FittedPage(bitmap, width, height, crop, layout)
    }

    /** The [crop] (page pixels) of a page decoded smaller, scaled to [fitWidth] × [fitHeight]. */
    private fun cutAndScale(decoded: Bitmap, crop: PixelRect, pageWidth: Int, pageHeight: Int, fitWidth: Int, fitHeight: Int): Bitmap {
        val sx = decoded.width.toFloat() / pageWidth
        val sy = decoded.height.toFloat() / pageHeight
        val left = floor(crop.left * sx).toInt().coerceIn(0, decoded.width - 1)
        val top = floor(crop.top * sy).toInt().coerceIn(0, decoded.height - 1)
        val right = ceil(crop.right * sx).toInt().coerceIn(left + 1, decoded.width)
        val bottom = ceil(crop.bottom * sy).toInt().coerceIn(top + 1, decoded.height)
        val cutWidth = right - left
        val cutHeight = bottom - top
        if (left == 0 && top == 0 && cutWidth == decoded.width && cutHeight == decoded.height && cutWidth <= fitWidth) return decoded
        val scale = Matrix().apply { setScale(fitWidth.toFloat() / cutWidth, fitHeight.toFloat() / cutHeight) }
        return Bitmap.createBitmap(decoded, left, top, cutWidth, cutHeight, scale, true)
    }

    /** The picture turned a quarter turn to the left (its top to the left side). The original is recycled. */
    fun turnLeft(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply { postRotate(-90f) }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
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
     * Looks at the page once (CLAUDE.md 12): decoded small (at least [PANEL_GRID_LONG_SIDE] px
     * on its long side, shrunk by a power of 2), shrunk to that size, then [analysePage] finds
     * its crop, its levels and its panels. Null when the bytes aren't a picture Android can read.
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
        val small = shrinkColorToLongSide(ColorImage(decoded.width, decoded.height, argb))
        decoded.recycle()
        val look = analysePage(small, width, height)
        val crop = look.crop.takeUnless { it == PixelRect(0, 0, width, height) }
        return PagePanels(width, height, look.panels.map(PanelBox::of), crop?.let(PanelBox::of), LevelsBox.of(look.levels))
    }
}
