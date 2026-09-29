package com.thelightphone.reader.comics

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The connected-region panel detector (CLAUDE.md 12), tried before the XY-cut. Plain Kotlin,
 * unit-tested.
 *
 * Every pixel of the gutter's color is marked, then the gutter is flooded from the page's
 * border: whatever the flood can't reach is content (ink, and the white inside panels and
 * balloons). Each connected piece of content is a panel candidate. Unlike the XY-cut this
 * needs no straight gutter right across the page, so slightly tilted scans and rows that don't
 * line up still split. Then:
 * - a region that is two panels joined by a balloon across their gutter is cut there (lines
 *   that are mostly gutter, between two solid panel edges);
 * - if any region big enough for a panel is far from filling its box (figures on a dark cover
 *   the flood ran round), none is kept;
 * - specks are dropped; balloons, captions and thin strips near a panel join it;
 * - boxes that overlap a lot become one;
 * - a box that is really several panels the flood couldn't get between (their gutter closed
 *   off from the page's edge) is cut along those gutters, the same way (lines of gutter
 *   color between two solid panel edges);
 * - panels are put in Western reading order, rows grouped by how much they overlap.
 */

/** The panel candidates on [image] (in its pixels, reading order) along gutters of the gray [gutter]. Not yet checked for [isReliableSplit]. */
fun findRegionPanels(image: GrayImage, gutter: Int, tuning: PanelTuning = PanelTuning()): List<PixelRect> =
    RegionFinder(image, gutter, tuning).panels()

/**
 * [panels] in Western reading order: grouped into rows (panels whose heights overlap by half
 * the smaller), top to bottom; inside a row into columns the same way, left to right; inside
 * a column into rows again, and so on. A slightly tilted row still reads left to right.
 */
fun readingOrder(panels: List<PixelRect>): List<PixelRect> = orderBands(panels, acrossRows = true, depth = 0)

private fun orderBands(panels: List<PixelRect>, acrossRows: Boolean, depth: Int): List<PixelRect> {
    if (panels.size <= 1) return panels
    val bands = bands(panels, acrossRows)
    if (bands.size > 1) return bands.flatMap { orderBands(it, !acrossRows, depth + 1) }
    // One band this way: try the other way (a tall panel beside two stacked ones).
    val other = bands(panels, !acrossRows)
    if (other.size == 1 || depth >= MAX_ORDER_DEPTH) return panels.sortedWith(compareBy({ it.top }, { it.left }))
    return other.flatMap { orderBands(it, acrossRows, depth + 1) }
}

/** [panels] grouped into rows (or columns), in order: each joins the band it overlaps by half its (or the band's) size. */
private fun bands(panels: List<PixelRect>, acrossRows: Boolean): List<List<PixelRect>> {
    fun start(p: PixelRect) = if (acrossRows) p.top else p.left
    fun end(p: PixelRect) = if (acrossRows) p.bottom else p.right
    val bands = mutableListOf<MutableList<PixelRect>>()
    var bandStart = 0
    var bandEnd = 0
    for (p in panels.sortedWith(compareBy({ start(it) }, { if (acrossRows) it.left else it.top }))) {
        val overlap = min(end(p), bandEnd) - max(start(p), bandStart)
        val smaller = min(end(p) - start(p), bandEnd - bandStart)
        if (bands.isNotEmpty() && overlap * 2 >= smaller) {
            bands.last().add(p)
            bandEnd = max(bandEnd, end(p))
        } else {
            bands.add(mutableListOf(p))
            bandStart = start(p)
            bandEnd = end(p)
        }
    }
    return bands
}

private const val MAX_ORDER_DEPTH = 6

/** The smallest rectangle holding both. */
fun union(a: PixelRect, b: PixelRect): PixelRect =
    PixelRect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))

/** How far apart two rectangles are: the larger of the gaps across and down (0 when they touch or overlap). */
fun gapBetween(a: PixelRect, b: PixelRect): Int {
    val across = max(0, max(a.left, b.left) - min(a.right, b.right))
    val down = max(0, max(a.top, b.top) - min(a.bottom, b.bottom))
    return max(across, down)
}

private fun overlapArea(a: PixelRect, b: PixelRect): Long {
    val w = min(a.right, b.right) - max(a.left, b.left)
    val h = min(a.bottom, b.bottom) - max(a.top, b.top)
    return if (w <= 0 || h <= 0) 0 else w.toLong() * h
}

private fun area(r: PixelRect): Long = r.width.toLong() * r.height

/** The flood, the regions and the clean-up, on one small picture. */
private class RegionFinder(private val image: GrayImage, private val gutter: Int, private val tuning: PanelTuning) {
    private val width = image.width
    private val height = image.height
    private val minGutter = max(2, (max(width, height) * tuning.minGutterShare).roundToInt())

    /** Per pixel: [OUTSIDE] (gutter the flood reached) or the number of the content region it's in. */
    private val labels = IntArray(width * height) { UNSEEN }
    private val regions = mutableListOf<Region>()

    private class Region(val label: Int, var box: PixelRect, var pixels: Int)

    /** Per pixel: the gutter's color (whether or not the flood reached it). */
    private val gutterPixel = BooleanArray(width * height) { isGutterLevel(image.pixels[it].toInt() and 0xFF, gutter, tuning) }

    init {
        val queue = IntArray(width * height)
        floodGutter(queue)
        labelContent(queue)
    }

    fun panels(): List<PixelRect> {
        val pageArea = width.toLong() * height
        val pieces = regions
            .filter { it.pixels >= tuning.speckShare * pageArea }
            .flatMap { region -> split(region.box, depth = 0, tuning.bridgeShare) { labels[it] == region.label } }
        val panels = pieces.filter(::isPanelSized).toMutableList()
        // Blobs of art the flood ran round (figures on a dark cover) aren't panels.
        if (panels.any { solidShare(it) < tuning.minPanelFill }) return emptyList()
        joinSmallPieces(pieces.filterNot(::isPanelSized), panels)
        mergeOverlaps(panels)
        return readingOrder(panels.flatMap(::cutAlongClosedGutters))
    }

    /** The share of [box] that is content, not gutter the flood reached. */
    private fun solidShare(box: PixelRect): Double {
        var outside = 0
        for (y in box.top until box.bottom) for (x in box.left until box.right) if (labels[y * width + x] == OUTSIDE) outside++
        return 1 - outside.toDouble() / area(box)
    }

    /**
     * [box] cut along gutters the flood couldn't reach (closed off from the page's edge): lines
     * almost all of gutter color (as the XY-cut asks) between two solid panel edges, with
     * every pixel not of the gutter's color as ink. Only when every piece is panel-sized.
     */
    private fun cutAlongClosedGutters(box: PixelRect): List<PixelRect> {
        val pieces = split(box, depth = 0, maxInkShare = 1 - tuning.gutterShare) { !gutterPixel[it] }
        return if (pieces.size >= 2 && pieces.all(::isPanelSized)) pieces else listOf(box)
    }

    // --- the flood and the regions -------------------------------------------------------

    /** Marks as [OUTSIDE] every gutter pixel the border reaches through other gutter pixels (up, down, left, right). */
    private fun floodGutter(queue: IntArray) {
        var tail = 0
        fun seed(i: Int) {
            if (gutterPixel[i] && labels[i] == UNSEEN) {
                labels[i] = OUTSIDE
                queue[tail++] = i
            }
        }
        for (x in 0 until width) {
            seed(x)
            seed((height - 1) * width + x)
        }
        for (y in 0 until height) {
            seed(y * width)
            seed(y * width + width - 1)
        }
        var head = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % width
            if (x > 0) seed(i - 1)
            if (x < width - 1) seed(i + 1)
            if (i >= width) seed(i - width)
            if (i < (height - 1) * width) seed(i + width)
        }
    }

    /** Numbers each connected piece of content (up, down, left, right), noting its box and size. */
    private fun labelContent(queue: IntArray) {
        for (start in labels.indices) {
            if (labels[start] != UNSEEN) continue
            val label = regions.size
            labels[start] = label
            queue[0] = start
            var head = 0
            var tail = 1
            var left = width
            var top = height
            var right = 0
            var bottom = 0
            fun visit(j: Int) {
                if (labels[j] == UNSEEN) {
                    labels[j] = label
                    queue[tail++] = j
                }
            }
            while (head < tail) {
                val i = queue[head++]
                val x = i % width
                val y = i / width
                left = min(left, x)
                right = max(right, x + 1)
                top = min(top, y)
                bottom = max(bottom, y + 1)
                if (x > 0) visit(i - 1)
                if (x < width - 1) visit(i + 1)
                if (y > 0) visit(i - width)
                if (y < height - 1) visit(i + width)
            }
            regions.add(Region(label, PixelRect(left, top, right, bottom), tail))
        }
    }

    // --- cutting joined panels apart -----------------------------------------------------

    /**
     * The [ink] pixels (by index) inside [area], cut where two panels meet across a gutter: first
     * into rows, else columns, each piece again. A piece that won't cut is one box.
     */
    private fun split(area: PixelRect, depth: Int, maxInkShare: Double, ink: (Int) -> Boolean): List<PixelRect> {
        val box = tightBox(area, ink) ?: return emptyList()
        if (depth >= MAX_SPLIT_DEPTH) return listOf(box)
        for (acrossRows in listOf(true, false)) {
            val pieces = gutterCuts(box, acrossRows, maxInkShare, ink)
            if (pieces.size >= 2) return pieces.flatMap { split(it, depth + 1, maxInkShare, ink) }
        }
        return listOf(box)
    }

    /** The smallest box holding the [ink] pixels inside [area]; null when there are none. */
    private fun tightBox(area: PixelRect, ink: (Int) -> Boolean): PixelRect? {
        var left = area.right
        var top = area.bottom
        var right = area.left
        var bottom = area.top
        for (y in area.top until area.bottom) {
            for (x in area.left until area.right) {
                if (!ink(y * width + x)) continue
                left = min(left, x)
                right = max(right, x + 1)
                top = min(top, y)
                bottom = max(bottom, y + 1)
            }
        }
        return PixelRect(left, top, right, bottom).takeUnless { it.isEmpty }
    }

    /**
     * [box] cut across every gutter between two panels: at least [minGutter] lines where [ink]
     * fills at most [maxInkShare] of the line (a balloon may cross it), with lines on both sides
     * it fills at least [PanelTuning.edgeShare] of (the panels' edges; a row of white balloons
     * has none). Cut in the run's middle; no piece thinner than [PanelTuning.minPanelShare] of
     * the page.
     */
    private fun gutterCuts(box: PixelRect, acrossRows: Boolean, maxInkShare: Double, ink: (Int) -> Boolean): List<PixelRect> {
        val start = if (acrossRows) box.top else box.left
        val end = if (acrossRows) box.bottom else box.right
        val length = if (acrossRows) box.width else box.height
        val own = IntArray(end - start) { i -> inkInLine(box, start + i, acrossRows, ink) }
        fun solidNear(from: Int, to: Int) = (from until to).any { it - start in own.indices && own[it - start] >= tuning.edgeShare * length }
        val minThickness = tuning.minPanelShare * (if (acrossRows) height else width)
        val cuts = mutableListOf<Int>()
        var runStart = -1
        for (i in start..end) {
            val bridged = i < end && own[i - start] <= maxInkShare * length
            if (bridged && runStart < 0) runStart = i
            if (!bridged && runStart >= 0) {
                val cut = (runStart + i) / 2
                val edges = runStart > start && i < end && solidNear(runStart - EDGE_REACH, runStart) && solidNear(i, i + EDGE_REACH)
                val thickEnough = cut - (cuts.lastOrNull() ?: start) >= minThickness && end - cut >= minThickness
                if (i - runStart >= minGutter && edges && thickEnough) cuts.add(cut)
                runStart = -1
            }
        }
        if (cuts.isEmpty()) return emptyList()
        val bounds = listOf(start) + cuts + listOf(end)
        return bounds.zipWithNext { a, b ->
            if (acrossRows) PixelRect(box.left, a, box.right, b) else PixelRect(a, box.top, b, box.bottom)
        }
    }

    private fun inkInLine(box: PixelRect, line: Int, acrossRows: Boolean, ink: (Int) -> Boolean): Int {
        var count = 0
        if (acrossRows) {
            for (x in box.left until box.right) if (ink(line * width + x)) count++
        } else {
            for (y in box.top until box.bottom) if (ink(y * width + line)) count++
        }
        return count
    }

    // --- cleaning up ------------------------------------------------------------------------

    /** Big enough for a panel: at least [PanelTuning.minPanelArea] of the page, and not a thin strip either way. */
    private fun isPanelSized(r: PixelRect): Boolean =
        area(r) >= tuning.minPanelArea * width * height &&
            r.width >= tuning.minPanelShare * width &&
            r.height >= tuning.minPanelShare * height

    /**
     * Balloons, captions and strips join the nearest panel when they're close to it, unless
     * that would stretch the panel over another one (a page title running above two panels).
     * The rest (page numbers, titles) are dropped.
     */
    private fun joinSmallPieces(small: List<PixelRect>, panels: MutableList<PixelRect>) {
        val reach = tuning.joinGapShare * max(width, height)
        for (piece in small.sortedByDescending(::area)) {
            val nearest = panels.indices.minByOrNull { gapBetween(panels[it], piece) } ?: continue
            if (gapBetween(panels[nearest], piece) > reach) continue
            val joined = union(panels[nearest], piece)
            if (panels.indices.none { it != nearest && overlapArea(joined, panels[it]) > 0 }) panels[nearest] = joined
        }
    }

    /** Boxes overlapping by [PanelTuning.overlapShare] of the smaller become one, until none do. */
    private fun mergeOverlaps(panels: MutableList<PixelRect>) {
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in panels.indices) {
                for (j in i + 1 until panels.size) {
                    val smaller = min(area(panels[i]), area(panels[j]))
                    if (overlapArea(panels[i], panels[j]) >= tuning.overlapShare * smaller) {
                        panels[i] = union(panels[i], panels[j])
                        panels.removeAt(j)
                        merged = true
                        break@loop
                    }
                }
            }
        }
    }

    companion object {
        private const val UNSEEN = -2
        private const val OUTSIDE = -1
        private const val MAX_SPLIT_DEPTH = 4

        /** A panel's edge must be solid within this many lines of the gutter a balloon crosses. */
        private const val EDGE_REACH = 3
    }
}
