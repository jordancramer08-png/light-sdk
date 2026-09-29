package com.thelightphone.reader.comics

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Finds the panels on a comic page (CLAUDE.md 12, part 3), in plain Kotlin so it runs in
 * PC tests. No Android here.
 *
 * The page comes in as a small gray picture (long side about [PANEL_GRID_LONG_SIDE]). The
 * gutter color (the white or black between panels) is read from the page's border, or, when
 * panels run off the page, white and then black are tried. Then the
 * page is cut again and again along rows or columns that are almost all gutter (an XY-cut):
 * first into rows of panels, top to bottom, then each row into panels, left to right, and so
 * on inside each piece. The panels come back in page pixels, in Western reading order.
 * A page with no reliable split comes back with no panels: the viewer shows it whole.
 */

/** Pages are shrunk to about this many pixels on their long side before panels are looked for. */
const val PANEL_GRID_LONG_SIDE = 800

/** Bump when the detector changes, so cached panels are looked for again. */
const val PANEL_DETECTOR_VERSION = 1

/**
 * The detector's thresholds. The defaults were tuned on real pages (CLAUDE.md 12, part 3);
 * the report test can try others.
 */
data class PanelTuning(
    /** A row or column counts as gutter when at least this share of its pixels is the gutter color. */
    val gutterShare: Double = 0.95,
    /** A pixel is the gutter color when it's no further than this from the gutter's gray, toward the ink. */
    val gutterTolerance: Int = 64,
    /** The thinnest gutter that splits panels, as a share of the page's long side (0.25%: 2 px of 800). */
    val minGutterShare: Double = 0.0025,
    /** A piece thinner than this share of the page (across the cut) is a sliver, not a panel. */
    val minPanelShare: Double = 0.06,
    /** A sliver with less ink than this share of its area is a speck or a page number: dropped. */
    val speckInkShare: Double = 0.02,
    /** Panels covering less of the page than this mean the split isn't reliable. */
    val minCoverage: Double = 0.6,
    /** More panels than this on one page mean the cut went through the art. */
    val maxPanels: Int = 16,
    /** A panel smaller than this share of the page means the cut went through the art. */
    val minPanelArea: Double = 0.01,
    /** A panel bigger than this share of the page is a splash: the whole page is shown instead. */
    val maxPanelArea: Double = 0.7,
    /** The gutter color must fill at least this share of the page's border, else the art runs off the page. */
    val minBorderShare: Double = 0.5,
)

/** A page's pixels as gray levels, 0 (black) to 255 (white), row by row. */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "Bad size $width x $height" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF
}

/** Colored pixels (0xAARRGGBB, as Android and Java hand them out) as gray levels. */
fun grayFromArgb(argb: IntArray, width: Int, height: Int): GrayImage {
    val gray = ByteArray(width * height)
    for (i in gray.indices) {
        val c = argb[i]
        val red = (c shr 16) and 0xFF
        val green = (c shr 8) and 0xFF
        val blue = c and 0xFF
        gray[i] = ((red * 299 + green * 587 + blue * 114) / 1000).toByte()
    }
    return GrayImage(width, height, gray)
}

/** The picture shrunk (each new pixel the average of the ones it covers) so its long side is [longSide]. */
fun shrinkToLongSide(image: GrayImage, longSide: Int = PANEL_GRID_LONG_SIDE): GrayImage {
    val scale = longSide.toDouble() / max(image.width, image.height)
    if (scale >= 1.0) return image
    val width = max(1, (image.width * scale).roundToInt())
    val height = max(1, (image.height * scale).roundToInt())
    val sums = LongArray(width * height)
    val counts = IntArray(width * height)
    for (y in 0 until image.height) {
        val row = (y.toLong() * height / image.height).toInt() * width
        for (x in 0 until image.width) {
            val i = row + (x.toLong() * width / image.width).toInt()
            sums[i] += image[x, y].toLong()
            counts[i]++
        }
    }
    val pixels = ByteArray(width * height) { i -> (sums[i] / max(1, counts[i])).toInt().toByte() }
    return GrayImage(width, height, pixels)
}

/**
 * The panels on a page, in reading order, in the page's own pixels ([pageWidth] × [pageHeight],
 * the full picture that [image] was shrunk from). Empty when the page has no reliable split.
 */
fun detectPanels(
    image: GrayImage,
    pageWidth: Int = image.width,
    pageHeight: Int = image.height,
    tuning: PanelTuning = PanelTuning(),
): List<PixelRect> {
    val search = searchPanels(image, tuning)
    if (!search.reliable) return emptyList()
    return search.pieces.map { toPage(it, image, pageWidth, pageHeight) }
}

/**
 * What the cut found on one page: the gutter gray it cut along, the pieces in reading order
 * (in [GrayImage] pixels), and whether they're a [reliable] split into panels.
 */
class PanelSearch(val gutter: Int, val pieces: List<PixelRect>, val reliable: Boolean)

/**
 * Cuts the page along the gutter color its border shows. When the border shows none (panels
 * running off the page), tries white gutters, then black, and keeps the first reliable split.
 */
fun searchPanels(image: GrayImage, tuning: PanelTuning = PanelTuning()): PanelSearch {
    val gutters = gutterLevel(image, tuning)?.let { listOf(it) } ?: listOf(WHITE, BLACK)
    val searches = gutters.map { gutter ->
        val pieces = PanelCutter(image, gutter, tuning).panels()
        PanelSearch(gutter, pieces, isReliableSplit(pieces, image, tuning))
    }
    return searches.firstOrNull { it.reliable } ?: searches.first()
}

/**
 * 2 to [PanelTuning.maxPanels] panels, none tiny and none filling most of the page, together
 * covering at least [PanelTuning.minCoverage] of it.
 */
fun isReliableSplit(candidates: List<PixelRect>, image: GrayImage, tuning: PanelTuning = PanelTuning()): Boolean {
    if (candidates.size !in 2..tuning.maxPanels) return false
    val pageArea = image.width.toLong() * image.height
    val areas = candidates.map { it.width.toLong() * it.height }
    if (areas.any { it < tuning.minPanelArea * pageArea || it > tuning.maxPanelArea * pageArea }) return false
    return panelCoverage(candidates, image) >= tuning.minCoverage
}

/** The share of the page the panels cover. */
fun panelCoverage(panels: List<PixelRect>, image: GrayImage): Double =
    panels.sumOf { it.width.toLong() * it.height }.toDouble() / (image.width.toLong() * image.height)

/**
 * The gutter's gray level, read from the page's outer border: the middle gray of its light
 * pixels or of its dark ones, whichever fill more of it. Null when neither fills
 * [PanelTuning.minBorderShare] of the border: the art runs off the page, so there's no gutter.
 */
fun gutterLevel(image: GrayImage, tuning: PanelTuning = PanelTuning()): Int? {
    val band = max(1, (minOf(image.width, image.height) * BORDER_BAND_SHARE).roundToInt())
    val counts = IntArray(256)
    for (y in 0 until image.height) {
        val inTopOrBottom = y < band || y >= image.height - band
        for (x in 0 until image.width) {
            if (inTopOrBottom || x < band || x >= image.width - band) counts[image[x, y]]++
        }
    }
    val light = LIGHT_GUTTER_LEVEL..255
    val dark = 0..DARK_GUTTER_LEVEL
    val lightCount = light.sumOf { counts[it] }
    val darkCount = dark.sumOf { counts[it] }
    val levels = if (lightCount >= darkCount) light else dark
    val inLevels = maxOf(lightCount, darkCount)
    if (inLevels < tuning.minBorderShare * counts.sum()) return null
    var seen = 0
    for (level in levels) {
        seen += counts[level]
        if (seen * 2 > inLevels) return level
    }
    return levels.last
}

/** The page's border, for [gutterLevel]: this share of its shorter side, all round. */
private const val BORDER_BAND_SHARE = 0.02

private const val WHITE = 255
private const val BLACK = 0

/** A light gutter (white or yellowed paper) is at least this gray; a dark one at most [DARK_GUTTER_LEVEL]. */
private const val LIGHT_GUTTER_LEVEL = 160
private const val DARK_GUTTER_LEVEL = 80

/** A rectangle found on the small picture, in the page's own pixels (never smaller, so no ink is cut off). */
private fun toPage(rect: PixelRect, image: GrayImage, pageWidth: Int, pageHeight: Int): PixelRect {
    val sx = pageWidth.toDouble() / image.width
    val sy = pageHeight.toDouble() / image.height
    return PixelRect(
        left = floor(rect.left * sx).toInt().coerceIn(0, pageWidth),
        top = floor(rect.top * sy).toInt().coerceIn(0, pageHeight),
        right = ceil(rect.right * sx).toInt().coerceIn(0, pageWidth),
        bottom = ceil(rect.bottom * sy).toInt().coerceIn(0, pageHeight),
    )
}

/** The XY-cut itself, on one small picture, along gutters of the gray [gutter]. */
private class PanelCutter(private val image: GrayImage, gutter: Int, private val tuning: PanelTuning) {
    private val width = image.width
    private val height = image.height
    private val longSide = max(width, height)
    private val minGutter = max(2, (longSide * tuning.minGutterShare).roundToInt())

    // For each row, how many gutter pixels lie left of each x; for each column, above each y.
    private val rowSums = IntArray((width + 1) * height)
    private val columnSums = IntArray((height + 1) * width)

    init {
        // A light gutter takes in anything lighter than the tolerance allows, a dark one anything darker.
        val lightGutter = gutter >= 128
        for (y in 0 until height) {
            for (x in 0 until width) {
                val level = image[x, y]
                val close = if (lightGutter) level >= gutter - tuning.gutterTolerance else level <= gutter + tuning.gutterTolerance
                val isGutter = if (close) 1 else 0
                rowSums[y * (width + 1) + x + 1] = rowSums[y * (width + 1) + x] + isGutter
                columnSums[x * (height + 1) + y + 1] = columnSums[x * (height + 1) + y] + isGutter
            }
        }
    }

    fun panels(): List<PixelRect> = split(PixelRect(0, 0, width, height), depth = 0)

    /** Cuts [area] into rows if it can, else into columns, and each piece again; a piece that won't cut is a panel. */
    private fun split(area: PixelRect, depth: Int): List<PixelRect> {
        val trimmed = trim(area) ?: return emptyList()
        if (depth >= MAX_DEPTH) return listOf(trimmed)
        val rows = pieces(trimmed, acrossRows = true)
        if (rows.size >= 2) return rows.flatMap { split(it, depth + 1) }
        val columns = pieces(trimmed, acrossRows = false)
        if (columns.size >= 2) return columns.flatMap { split(it, depth + 1) }
        return listOf(trimmed)
    }

    private fun gutterInRow(y: Int, left: Int, right: Int): Int =
        rowSums[y * (width + 1) + right] - rowSums[y * (width + 1) + left]

    private fun gutterInColumn(x: Int, top: Int, bottom: Int): Int =
        columnSums[x * (height + 1) + bottom] - columnSums[x * (height + 1) + top]

    private fun isGutterRow(y: Int, area: PixelRect) =
        gutterInRow(y, area.left, area.right) >= tuning.gutterShare * area.width

    private fun isGutterColumn(x: Int, area: PixelRect) =
        gutterInColumn(x, area.top, area.bottom) >= tuning.gutterShare * area.height

    /** [area] without the gutter rows and columns around its edges; null when it's all gutter. */
    private fun trim(area: PixelRect): PixelRect? {
        var (left, top, right, bottom) = area
        while (top < bottom && isGutterRow(top, PixelRect(left, top, right, bottom))) top++
        while (bottom > top && isGutterRow(bottom - 1, PixelRect(left, top, right, bottom))) bottom--
        while (left < right && isGutterColumn(left, PixelRect(left, top, right, bottom))) left++
        while (right > left && isGutterColumn(right - 1, PixelRect(left, top, right, bottom))) right--
        return PixelRect(left, top, right, bottom).takeUnless { it.isEmpty }
    }

    /**
     * [area] cut along every gutter at least [minGutter] thick: rows top to bottom when
     * [acrossRows], else columns left to right. Slivers are dropped or joined to a neighbour.
     */
    private fun pieces(area: PixelRect, acrossRows: Boolean): List<PixelRect> {
        val start = if (acrossRows) area.top else area.left
        val end = if (acrossRows) area.bottom else area.right
        val runs = mutableListOf<IntRange>()
        var pieceStart = start
        var gutterStart = -1
        for (i in start until end) {
            val gutter = if (acrossRows) isGutterRow(i, area) else isGutterColumn(i, area)
            if (gutter && gutterStart < 0) gutterStart = i
            if (!gutter && gutterStart >= 0) {
                if (i - gutterStart >= minGutter && gutterStart > pieceStart) {
                    runs.add(pieceStart until gutterStart)
                    pieceStart = i
                }
                gutterStart = -1
            }
        }
        runs.add(pieceStart until end)
        val rects = runs.map { run ->
            if (acrossRows) PixelRect(area.left, run.first, area.right, run.last + 1)
            else PixelRect(run.first, area.top, run.last + 1, area.bottom)
        }
        return withoutSlivers(rects, acrossRows)
    }

    /** Drops specks and joins each thin piece to the neighbour it's closest to. */
    private fun withoutSlivers(pieces: List<PixelRect>, acrossRows: Boolean): List<PixelRect> {
        val minThickness = tuning.minPanelShare * (if (acrossRows) height else width)
        val kept = pieces.filterNot { thickness(it, acrossRows) < minThickness && isSpeck(it) }.toMutableList()
        while (kept.size >= 2) {
            val thin = kept.indices.firstOrNull { thickness(kept[it], acrossRows) < minThickness } ?: break
            val before = kept.getOrNull(thin - 1)
            val after = kept.getOrNull(thin + 1)
            val joinBefore = after == null ||
                (before != null && gap(before, kept[thin], acrossRows) <= gap(kept[thin], after, acrossRows))
            if (joinBefore) {
                kept[thin - 1] = union(before!!, kept[thin])
            } else {
                kept[thin + 1] = union(kept[thin], after!!)
            }
            kept.removeAt(thin)
        }
        return kept
    }

    private fun thickness(rect: PixelRect, acrossRows: Boolean) = if (acrossRows) rect.height else rect.width

    private fun gap(first: PixelRect, second: PixelRect, acrossRows: Boolean) =
        if (acrossRows) second.top - first.bottom else second.left - first.right

    private fun isSpeck(rect: PixelRect): Boolean {
        var gutter = 0L
        for (y in rect.top until rect.bottom) gutter += gutterInRow(y, rect.left, rect.right)
        val area = rect.width.toLong() * rect.height
        return area - gutter < tuning.speckInkShare * area
    }

    private fun union(a: PixelRect, b: PixelRect) =
        PixelRect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))

    companion object {
        private const val MAX_DEPTH = 8
    }
}
