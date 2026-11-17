package com.thelightphone.reader.comics

import kotlin.math.abs
import kotlin.math.ceil
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

    /** Pixel counts tuned at 800 px are scaled by this on a bigger picture (the second look). */
    private val scale = max(1.0, max(width, height) / PANEL_GRID_LONG_SIDE.toDouble())
    private val pageArea = width.toLong() * height
    private val mask = PaperMask(width, height, gutterPixel)

    fun panels(): List<PixelRect> {
        val pieces = regions
            .filter { it.pixels >= tuning.speckShare * pageArea }
            .flatMap { region ->
                val own = { i: Int -> labels[i] == region.label }
                erodedParts(region).flatMap { part -> split(part, depth = 0, tuning.bridgeShare, own) }
            }
        val (bleeds, rest) = mergeBleedingPanels(pieces)
        val panels = rest.filter(::isPanelSized).toMutableList()
        // Blobs of art the flood ran round (figures on a dark cover) aren't panels.
        if (panels.any { solidShare(it) < tuning.minPanelFill }) return emptyList()
        panels.addAll(bleeds)
        joinSmallPieces(rest.filterNot(::isPanelSized), panels)
        mergeOverlaps(panels)
        val cut = panels.flatMap(::cutAlongClosedGutters).toMutableList()
        attachStrayInk(cut)
        return readingOrder(cut)
    }

    // --- thin bridges -----------------------------------------------------------------------

    /**
     * [region]'s box, or, when eroding the region a little (a square of [erosionRadius]) breaks
     * it into two or more panel-sized parts (panels touching through a thin tail or a corner),
     * each part's box grown back by the radius. Panel interiors are already filled (the flood
     * can't reach inside a frame), so a real panel erodes to one slightly smaller part.
     */
    private fun erodedParts(region: Region): List<PixelRect> {
        val box = region.box
        if (area(box) < 2 * tuning.minPanelArea * pageArea) return listOf(box)
        val r = max(1, (EROSION_RADIUS * scale).roundToInt())
        val w = box.width
        val h = box.height
        val solid = BooleanArray(w * h) { labels[(box.top + it / w) * width + box.left + it % w] == region.label }
        val eroded = erode(solid, w, h, r)
        val parts = components(eroded, w, h).filter { it.second >= tuning.minPanelArea * pageArea / 2 }
        if (parts.size < 2) return listOf(box)
        val boxes = parts.map { (part, _) ->
            PixelRect(
                max(box.left, box.left + part.left - r), max(box.top, box.top + part.top - r),
                min(box.right, box.left + part.right + r), min(box.bottom, box.top + part.bottom + r),
            )
        }
        // Each part must be a solid panel of its own; else the region stays whole.
        return if (boxes.all { regionShare(it, region.label) >= tuning.minPanelFill }) boxes else listOf(box)
    }

    /** The share of [box] that belongs to region [label]. */
    private fun regionShare(box: PixelRect, label: Int): Double {
        var own = 0
        for (y in box.top until box.bottom) for (x in box.left until box.right) if (labels[y * width + x] == label) own++
        return own.toDouble() / area(box)
    }

    // --- panels bleeding off the page ---------------------------------------------------------

    /**
     * A panel whose art runs off the page has a frame on some sides only; where its art is light,
     * the flood comes in through the open sides and breaks it into pieces. Its drawn sides still
     * span it: a horizontal and a vertical frame edge meeting at a corner, or two opposite edges
     * over the same stretch, reaching the picture's edge on an open side. Two or more pieces
     * lying inside such a span become one panel (their union with the span). A span with another
     * long frame edge inside it holds several panels and is left alone. Returns (merged, the rest).
     */
    private fun mergeBleedingPanels(pieces: List<PixelRect>): Pair<List<PixelRect>, List<PixelRect>> {
        val spans = bleedSpans()
        if (spans.isEmpty()) return emptyList<PixelRect>() to pieces
        val rest = pieces.toMutableList()
        val merged = mutableListOf<PixelRect>()
        for (span in spans.sortedByDescending(::area)) {
            val inside = rest.filter { overlapArea(it, span) >= BLEED_INSIDE_SHARE * area(it) }
            // Paper flooded most of a bleeding panel; real panels inside a span would tile it.
            val solid = inside.sumOf { area(it) * solidShare(it) }
            if (inside.size < 2 || solid > BLEED_MAX_SOLID * area(span)) continue
            rest.removeAll(inside)
            merged.add(inside.fold(span, ::union))
        }
        return merged to rest
    }

    private fun bleedSpans(): List<PixelRect> {
        val paperRun = max(3, (3 * scale).roundToInt())
        val horizontal = frameEdges(mask, horizontal = true, minLength = (BLEED_EDGE_SHARE * width).roundToInt(), paperRun = paperRun)
        val vertical = frameEdges(mask, horizontal = false, minLength = (BLEED_EDGE_SHARE * height).roundToInt(), paperRun = paperRun)
        val near = max(3, (BLEED_NEAR_SHARE * max(width, height)).roundToInt())
        val spans = mutableListOf<PixelRect>()
        // Corners: the vertical edge leaves one end of the horizontal one, on the frame's inner side.
        for (h in horizontal) {
            for (v in vertical) {
                val panelRight = v.out < 0
                val panelBelow = h.out < 0
                val cornerX = if (panelRight) abs(v.at - h.lo) <= near else abs(v.at - h.hi) <= near
                val cornerY = if (panelBelow) abs(h.at - v.lo) <= near else abs(h.at - v.hi) <= near
                if (!cornerX || !cornerY) continue
                val span = PixelRect(
                    if (panelRight) v.at else h.lo, if (panelBelow) h.at else v.lo,
                    if (panelRight) h.hi else v.at + 1, if (panelBelow) v.hi else h.at + 1,
                )
                val openX = if (panelRight) span.right >= width - near else span.left <= near
                val openY = if (panelBelow) span.bottom >= height - near else span.top <= near
                if ((openX || openY) && !span.isEmpty) spans.add(span)
            }
        }
        // Opposite sides: an edge with paper before it and one with paper after it, over the same stretch.
        for (edges in listOf(horizontal, vertical)) {
            for (a in edges.filter { it.out < 0 }) {
                for (b in edges.filter { it.out > 0 && it.at > a.at }) {
                    val lo = max(a.lo, b.lo)
                    val hi = min(a.hi, b.hi)
                    if (hi - lo < OPPOSITE_OVERLAP * min(a.length, b.length)) continue
                    val horizontalPair = a.horizontal
                    val thickness = b.at - a.at
                    if (thickness < tuning.minPanelShare * (if (horizontalPair) height else width)) continue
                    val span = if (horizontalPair) PixelRect(lo, a.at, hi, b.at + 1) else PixelRect(a.at, lo, b.at + 1, hi)
                    val open = if (horizontalPair) span.left <= near || span.right >= width - near else span.top <= near || span.bottom >= height - near
                    if (open) spans.add(span)
                }
            }
        }
        val all = horizontal + vertical
        return spans.filter { span -> all.none { holdsInnerEdge(span, it, near) } }
    }

    /** True when [edge] lies inside [span], away from its sides, over half the span's extent: a gutter between panels in it. */
    private fun holdsInnerEdge(span: PixelRect, edge: FrameEdge, near: Int): Boolean {
        val (spanLo, spanHi) = if (edge.horizontal) span.left to span.right else span.top to span.bottom
        val (across0, across1) = if (edge.horizontal) span.top to span.bottom else span.left to span.right
        if (edge.at <= across0 + near || edge.at >= across1 - near) return false
        val overlap = min(edge.hi, spanHi) - max(edge.lo, spanLo)
        return overlap * 2 > spanHi - spanLo
    }

    // --- stray ink ------------------------------------------------------------------------------

    /**
     * Ink outside every panel (a caption, a title, loose lettering) joins the panel whose cell
     * holds it. Each panel's cell is its box grown in every direction to the middle of the gap to
     * its nearest neighbour that way, or to the picture's edge. Small pieces of content are first
     * grouped into blocks (letters into words, close lines into paragraphs). A block that would
     * make its panel overlap another is left out, and so are running heads and page numbers (thin
     * lines in the top or bottom [EDGE_BAND_SHARE] of the page) and ink touching the picture's edge
     * (scan shadow).
     */
    private fun attachStrayInk(panels: MutableList<PixelRect>) {
        if (panels.isEmpty()) return
        val minPixels = max(2, (STRAY_MIN_PIXELS * scale * scale).roundToInt())
        val stray = regions
            .filter { r -> r.pixels >= minPixels && panels.none { overlapArea(it, r.box) * 2 >= area(r.box) } }
            .filterNot { r -> r.box.left == 0 || r.box.top == 0 || r.box.right == width || r.box.bottom == height }
            .take(MAX_STRAY_PIECES)
            .map { it.box }
        val blocks = groupIntoBlocks(stray)
        val band = EDGE_BAND_SHARE * height
        val thin = HEAD_LINE_SHARE * height
        val cells = cells(panels)
        for (block in blocks.sortedByDescending(::area)) {
            val runningHead = block.height <= thin && (block.bottom <= band || block.top >= height - band)
            if (runningHead) continue
            val cx = (block.left + block.right) / 2
            val cy = (block.top + block.bottom) / 2
            for (i in panels.indices.sortedBy { gapBetween(panels[it], block) }) {
                val cell = cells[i]
                if (cx !in cell.left until cell.right || cy !in cell.top until cell.bottom) continue
                val grown = union(panels[i], block)
                val before = panels.indices.filter { it != i }.sumOf { overlapArea(panels[i], panels[it]) }
                val after = panels.indices.filter { it != i }.sumOf { overlapArea(grown, panels[it]) }
                if (after <= before) panels[i] = grown
                break
            }
        }
    }

    /** Pieces whose boxes lie close together (across: [BLOCK_GAP_ACROSS], down: [BLOCK_GAP_DOWN] of the side) become one box. */
    private fun groupIntoBlocks(pieces: List<PixelRect>): List<PixelRect> {
        val gapX = max(2, (BLOCK_GAP_ACROSS * width).roundToInt())
        val gapY = max(1, (BLOCK_GAP_DOWN * height).roundToInt())
        val blocks = pieces.toMutableList()
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in blocks.indices) {
                for (j in i + 1 until blocks.size) {
                    val a = blocks[i]
                    val b = blocks[j]
                    val dx = max(a.left, b.left) - min(a.right, b.right)
                    val dy = max(a.top, b.top) - min(a.bottom, b.bottom)
                    if (dx <= gapX && dy <= gapY) {
                        blocks[i] = union(a, b)
                        blocks.removeAt(j)
                        merged = true
                        break@loop
                    }
                }
            }
        }
        return blocks
    }

    /** Each box grown to the middle of the gap to its nearest neighbour in every direction (one overlapping it across), else to the edge. */
    private fun cells(boxes: List<PixelRect>): List<PixelRect> = boxes.map { b ->
        val others = boxes.filter { it !== b }
        fun overlapsAcross(o: PixelRect) = min(o.right, b.right) > max(o.left, b.left)
        fun overlapsDown(o: PixelRect) = min(o.bottom, b.bottom) > max(o.top, b.top)
        val up = others.filter { overlapsAcross(it) && it.bottom <= b.top }.maxOfOrNull { it.bottom }
        val down = others.filter { overlapsAcross(it) && it.top >= b.bottom }.minOfOrNull { it.top }
        val left = others.filter { overlapsDown(it) && it.right <= b.left }.maxOfOrNull { it.right }
        val right = others.filter { overlapsDown(it) && it.left >= b.right }.minOfOrNull { it.left }
        PixelRect(
            left?.let { (it + b.left) / 2 } ?: 0,
            up?.let { (it + b.top) / 2 } ?: 0,
            right?.let { (it + b.right + 1) / 2 } ?: width,
            down?.let { (it + b.bottom + 1) / 2 } ?: height,
        )
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
        // A frame edge on a skewed scan drifts up to about a degree across the box: look that much further.
        val reach = (EDGE_REACH * scale).roundToInt() + ceil(length * SKEW_SLOPE).toInt()
        val cuts = mutableListOf<Int>()
        var runStart = -1
        for (i in start..end) {
            val bridged = i < end && own[i - start] <= maxInkShare * length
            if (bridged && runStart < 0) runStart = i
            if (!bridged && runStart >= 0) {
                val cut = balloonSideCut(own, start, runStart, i)
                val edges = runStart > start && i < end && solidNear(runStart - reach, runStart) && solidNear(i, i + reach)
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

    /**
     * Where to cut across the gutter run [runStart] until [runEnd]: a balloon crossing it isn't cut
     * in the gutter. A balloon bulges toward its middle, so it crosses the gutter thicker on the
     * side its middle is on: when the run's first line is clearly ([BALLOON_SIDE_DIFFERENCE])
     * fuller than its last, the whole run goes to the first side (cut at its far end), and the
     * other way round; else (centred, or nothing crossing) the cut is in its middle.
     */
    private fun balloonSideCut(own: IntArray, start: Int, runStart: Int, runEnd: Int): Int {
        val first = own[runStart - start]
        val last = own[runEnd - 1 - start]
        val clear = BALLOON_SIDE_DIFFERENCE * max(first, last)
        return when {
            first - last > clear && first > 0 -> runEnd
            last - first > clear && last > 0 -> runStart
            else -> (runStart + runEnd) / 2
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

        /** A panel's edge must be solid within this many lines of the gutter a balloon crosses (plus the skew allowance). */
        private const val EDGE_REACH = 3

        /** About a degree: how far a frame edge may drift on a skewed scan. */
        private val SKEW_SLOPE = kotlin.math.tan(Math.toRadians(1.0))

        /** Thin bridges between panels break when a region is eroded by this many pixels (at 800 px). */
        private const val EROSION_RADIUS = 2

        /** Frame edges spanning a bleeding panel are at least this share of the picture's side ... */
        private const val BLEED_EDGE_SHARE = 0.15

        /** ... meet at a corner, or reach the picture's edge, within this share of its long side ... */
        private const val BLEED_NEAR_SHARE = 0.015

        /** ... and a piece is inside the span when this share of it is. */
        private const val BLEED_INSIDE_SHARE = 0.8

        /** A bleeding panel's pieces fill at most this share of its span (the rest is flooded paper). */
        private const val BLEED_MAX_SOLID = 0.6

        /** Opposite frame edges overlap by this share of the shorter one. */
        private const val OPPOSITE_OVERLAP = 0.8

        /** A balloon's side of a gutter: its first and last lines differ by this share of the fuller. */
        private const val BALLOON_SIDE_DIFFERENCE = 0.1

        /** Stray ink: pieces of at least this many pixels (at 800 px; a letter, not a speck) ... */
        private const val STRAY_MIN_PIXELS = 12

        /** ... (at most this many looked at) ... */
        private const val MAX_STRAY_PIECES = 3000

        /** ... grouped when this close across (share of the width) ... */
        private const val BLOCK_GAP_ACROSS = 0.012

        /** ... and down (share of the height). */
        private const val BLOCK_GAP_DOWN = 0.006

        /** Running heads and page numbers: blocks in this share of the page at its top or bottom ... */
        private const val EDGE_BAND_SHARE = 0.05

        /** ... no taller than this share of it. */
        private const val HEAD_LINE_SHARE = 0.03
    }
}

/** [mask] ([w] × [h]) eroded by a square of radius [r]: a pixel stays when every pixel within [r] is set (outside counts as unset). */
private fun erode(mask: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
    val across = BooleanArray(w * h)
    for (y in 0 until h) {
        var run = 0
        for (x in 0 until w) {
            run = if (mask[y * w + x]) run + 1 else 0
            // The pixel r to the left of x stays when x - 2r .. x are all set.
            if (run >= 2 * r + 1) across[y * w + x - r] = true
        }
    }
    val out = BooleanArray(w * h)
    for (x in 0 until w) {
        var run = 0
        for (y in 0 until h) {
            run = if (across[y * w + x]) run + 1 else 0
            if (run >= 2 * r + 1) out[(y - r) * w + x] = true
        }
    }
    return out
}

/** The 4-connected pieces of [mask] ([w] × [h]): each one's box and pixel count. */
private fun components(mask: BooleanArray, w: Int, h: Int): List<Pair<PixelRect, Int>> {
    val seen = BooleanArray(w * h)
    val queue = IntArray(w * h)
    val out = mutableListOf<Pair<PixelRect, Int>>()
    for (start in mask.indices) {
        if (!mask[start] || seen[start]) continue
        seen[start] = true
        queue[0] = start
        var head = 0
        var tail = 1
        var left = w
        var top = h
        var right = 0
        var bottom = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            left = min(left, x)
            right = max(right, x + 1)
            top = min(top, y)
            bottom = max(bottom, y + 1)
            fun visit(j: Int) {
                if (mask[j] && !seen[j]) {
                    seen[j] = true
                    queue[tail++] = j
                }
            }
            if (x > 0) visit(i - 1)
            if (x < w - 1) visit(i + 1)
            if (y > 0) visit(i - w)
            if (y < h - 1) visit(i + w)
        }
        out.add(PixelRect(left, top, right, bottom) to tail)
    }
    return out
}
