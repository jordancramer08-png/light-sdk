package com.thelightphone.reader.comics

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The comic viewer's arithmetic (CLAUDE.md 12): where a tap lands, how a page fits the
 * screen, zooming and panning, and which part of the page is on screen. Plain Kotlin, so
 * it runs in PC tests. All sizes are pixels.
 */

/** Pinching stops here: 4 times the fitted size. */
const val MAX_ZOOM = 4f

/** A double-tap on a fitted page zooms this much, at the spot tapped. */
const val DOUBLE_TAP_ZOOM = 2.5f

/** A tap in the top 15% of the screen shows (or hides) the overlay. */
const val OVERLAY_TAP_FRACTION = 0.15f

/** A tap in the left 30% goes back a page, the rest goes forward (as in the book reader). */
const val PREVIOUS_PAGE_TAP_FRACTION = 0.3f

/** Anything closer to 1 than this counts as fitted (pinching back never lands exactly on 1). */
private const val FITTED_ZOOM_SLACK = 0.01f

/** What a single tap on the page does. */
enum class PageTap { TOGGLE_OVERLAY, PREVIOUS, NEXT, NOTHING }

/** Top 15%: the overlay (even while zoomed). Otherwise left 30% back, rest forward — or nothing while zoomed. */
fun pageTap(x: Float, y: Float, screenWidth: Int, screenHeight: Int, zoomed: Boolean): PageTap = when {
    y < screenHeight * OVERLAY_TAP_FRACTION -> PageTap.TOGGLE_OVERLAY
    zoomed -> PageTap.NOTHING
    x < screenWidth * PREVIOUS_PAGE_TAP_FRACTION -> PageTap.PREVIOUS
    else -> PageTap.NEXT
}

/** How much a page is scaled so it fits whole on the screen (below 1 shrinks it). */
fun fitScale(imageWidth: Int, imageHeight: Int, screenWidth: Int, screenHeight: Int): Float =
    min(screenWidth.toFloat() / imageWidth, screenHeight.toFloat() / imageHeight)

/**
 * How a page is zoomed: [zoom] 1 = fitted, up to [MAX_ZOOM]. [panX] / [panY] move the page's
 * centre away from the screen's centre, in screen pixels.
 */
data class PageZoom(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {
    val isZoomed: Boolean get() = zoom > 1f + FITTED_ZOOM_SLACK
}

/** A rectangle of a page's pixels (right and bottom not included). */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/** One page (its full size in pixels) on one screen: everything about placing and zooming it. */
class PageGeometry(
    val imageWidth: Int,
    val imageHeight: Int,
    val screenWidth: Int,
    val screenHeight: Int,
) {
    private val fit = fitScale(imageWidth, imageHeight, screenWidth, screenHeight)

    /** Screen pixels per page pixel at this zoom. */
    fun scale(zoom: PageZoom): Float = fit * zoom.zoom

    fun shownWidth(zoom: PageZoom): Float = imageWidth * scale(zoom)
    fun shownHeight(zoom: PageZoom): Float = imageHeight * scale(zoom)

    /** Where the page's left and top edges are on the screen. */
    fun pageLeft(zoom: PageZoom): Float = (screenWidth - shownWidth(zoom)) / 2f + zoom.panX
    fun pageTop(zoom: PageZoom): Float = (screenHeight - shownHeight(zoom)) / 2f + zoom.panY

    /**
     * Keeps the zoom between 1 and [MAX_ZOOM] and the page on the screen: a page wider than
     * the screen can move until its edge meets the screen's edge; one narrower stays centred.
     */
    fun clamp(zoom: PageZoom): PageZoom {
        val z = zoom.zoom.coerceIn(1f, MAX_ZOOM)
        val sized = zoom.copy(zoom = z)
        val spareX = max(0f, (shownWidth(sized) - screenWidth) / 2f)
        val spareY = max(0f, (shownHeight(sized) - screenHeight) / 2f)
        // "+ 0f" turns -0 into 0, so a fitted page always equals PageZoom().
        return PageZoom(z, zoom.panX.coerceIn(-spareX, spareX) + 0f, zoom.panY.coerceIn(-spareY, spareY) + 0f)
    }

    /** Zooms by [factor] keeping the page point under ([focusX], [focusY]) where it is. */
    fun zoomBy(zoom: PageZoom, factor: Float, focusX: Float, focusY: Float): PageZoom {
        val newZoom = (zoom.zoom * factor).coerceIn(1f, MAX_ZOOM)
        val change = newZoom / zoom.zoom
        val fromCentreX = focusX - screenWidth / 2f
        val fromCentreY = focusY - screenHeight / 2f
        return clamp(
            PageZoom(
                zoom = newZoom,
                panX = fromCentreX - (fromCentreX - zoom.panX) * change,
                panY = fromCentreY - (fromCentreY - zoom.panY) * change,
            ),
        )
    }

    /** Moves a zoomed page with the finger. */
    fun panBy(zoom: PageZoom, dx: Float, dy: Float): PageZoom =
        clamp(zoom.copy(panX = zoom.panX + dx, panY = zoom.panY + dy))

    /** Double-tap: a fitted page zooms to [DOUBLE_TAP_ZOOM] at the spot; a zoomed one fits again. */
    fun doubleTap(zoom: PageZoom, x: Float, y: Float): PageZoom =
        if (zoom.isZoomed) PageZoom() else zoomBy(PageZoom(), DOUBLE_TAP_ZOOM, x, y)

    /** The part of the page on screen, in page pixels (empty when none of it is). */
    fun visibleRegion(zoom: PageZoom): PixelRect {
        val scale = scale(zoom)
        val left = pageLeft(zoom)
        val top = pageTop(zoom)
        return PixelRect(
            left = floor(-left / scale).toInt().coerceIn(0, imageWidth),
            top = floor(-top / scale).toInt().coerceIn(0, imageHeight),
            right = ceil((screenWidth - left) / scale).toInt().coerceIn(0, imageWidth),
            bottom = ceil((screenHeight - top) / scale).toInt().coerceIn(0, imageHeight),
        )
    }

    /**
     * How much to shrink the page's pixels when decoding the part on screen (a power of 2):
     * as much as possible while every screen pixel still gets at least one page pixel.
     */
    fun regionSampleSize(zoom: PageZoom): Int {
        val pagePixelsPerScreenPixel = 1f / scale(zoom)
        var sample = 1
        while (sample * 2 <= pagePixelsPerScreenPixel) sample *= 2
        return sample
    }
}

/**
 * The size to decode a page at so it fits the screen, never bigger than the page itself
 * (a small page is drawn scaled up instead).
 */
fun fittedSize(imageWidth: Int, imageHeight: Int, screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
    val scale = min(1f, fitScale(imageWidth, imageHeight, screenWidth, screenHeight))
    return max(1, (imageWidth * scale).roundToInt()) to max(1, (imageHeight * scale).roundToInt())
}

/** The page (0-based) a comic opens on: its saved page (1-based), or the first; never past the end. */
fun openingPageIndex(savedPage: Int?, pageCount: Int): Int =
    ((savedPage ?: 1) - 1).coerceIn(0, max(0, pageCount - 1))

/**
 * The pages whose pictures stay in memory: the one shown and the next one in the direction
 * of reading ([direction] +1 forward, -1 back), when there is one.
 */
fun pagesToKeep(current: Int, direction: Int, pageCount: Int): Set<Int> {
    val neighbour = current + direction
    return if (neighbour in 0 until pageCount) setOf(current, neighbour) else setOf(current)
}

/** The page the slider points at: [fraction] 0 is the first page, 1 the last. */
fun sliderPage(fraction: Float, pageCount: Int): Int =
    (fraction.coerceIn(0f, 1f) * (pageCount - 1)).roundToInt().coerceIn(0, max(0, pageCount - 1))

/** Where the slider sits for [pageIndex]. */
fun sliderFraction(pageIndex: Int, pageCount: Int): Float =
    if (pageCount <= 1) 0f else pageIndex.toFloat() / (pageCount - 1)
