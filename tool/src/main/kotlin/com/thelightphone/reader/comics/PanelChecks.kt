package com.thelightphone.reader.comics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Checks shared by the panel detectors (CLAUDE.md 12, "Panel checks"). Plain Kotlin, unit-tested.
 *
 * - [frameEdges]: the straight drawn edges of panel frames (ink with paper right outside it),
 *   followed as they drift up to about 1 degree on a skewed scan.
 * - [panelDoubts] / [isDoubtful]: how far each found panel can be trusted.
 * - [looksLikePageFurniture]: contents, credits and title pages, kept whole.
 */

/** Paper (the gutter's color) or not, per pixel of a [width] × [height] picture. */
class PaperMask(val width: Int, val height: Int, private val paper: BooleanArray) {
    fun isPaper(x: Int, y: Int): Boolean = paper[y * width + x]
    fun isInk(x: Int, y: Int): Boolean = !paper[y * width + x]

    companion object {
        fun of(image: GrayImage, gutter: Int, tuning: PanelTuning): PaperMask =
            PaperMask(image.width, image.height, BooleanArray(image.width * image.height) {
                isGutterLevel(image.pixels[it].toInt() and 0xFF, gutter, tuning)
            })
    }
}

/**
 * A drawn frame edge: a line of ink with paper just outside it. [horizontal] edges sit at row
 * [at] from column [lo] to [hi] (exclusive); vertical ones at column [at] from row [lo] to [hi].
 * [out] is the side the paper is on: -1 above (or left), +1 below (or right). The panel lies on
 * the other side.
 */
data class FrameEdge(val horizontal: Boolean, val at: Int, val lo: Int, val hi: Int, val out: Int) {
    val length: Int get() = hi - lo
}

/** How far a frame edge may drift while it's followed: about 1.2 degrees. */
private val MAX_EDGE_SLOPE = tan(Math.toRadians(1.2))

/** Small breaks in a frame line (scan noise, a balloon's tail) up to this many pixels are bridged. */
private const val EDGE_GAP = 3

/**
 * Every frame edge at least [minLength] long, horizontal or vertical. An edge pixel is ink with
 * [paperRun] pixels of paper beyond it on its [FrameEdge.out] side. Each edge is followed a pixel
 * at a time, stepping a line up or down when the line drifts (a skewed scan), up to about a
 * degree; a figure's curved outline drifts further and stops.
 */
fun frameEdges(mask: PaperMask, horizontal: Boolean, minLength: Int, paperRun: Int = 3): List<FrameEdge> {
    val along = if (horizontal) mask.width else mask.height
    val across = if (horizontal) mask.height else mask.width
    fun ink(a: Int, c: Int) = if (horizontal) mask.isInk(a, c) else mask.isInk(c, a)
    fun paper(a: Int, c: Int) = if (horizontal) mask.isPaper(a, c) else mask.isPaper(c, a)
    fun isEdge(a: Int, c: Int, out: Int): Boolean {
        if (a !in 0 until along || c !in 0 until across || !ink(a, c)) return false
        for (k in 1..paperRun) {
            val p = c + out * k
            if (p !in 0 until across || !paper(a, p)) return false
        }
        return true
    }
    val edges = mutableListOf<FrameEdge>()
    for (out in intArrayOf(-1, 1)) {
        val used = BooleanArray(along * across)
        for (c0 in 0 until across) {
            for (a0 in 0 until along) {
                if (used[c0 * along + a0] || !isEdge(a0, c0, out)) continue
                var c = c0
                var a = a0
                var lastHit = a0
                var sum = 0L
                var hits = 0
                while (a < along && a - lastHit <= EDGE_GAP + 1) {
                    val budget = 1 + ((a - a0) * MAX_EDGE_SLOPE).toInt()
                    val next = when {
                        isEdge(a, c, out) -> c
                        abs(c - 1 - c0) <= budget && isEdge(a, c - 1, out) -> c - 1
                        abs(c + 1 - c0) <= budget && isEdge(a, c + 1, out) -> c + 1
                        else -> null
                    }
                    if (next != null) {
                        c = next
                        used[c * along + a] = true
                        lastHit = a
                        sum += c
                        hits++
                    }
                    a++
                }
                if (lastHit + 1 - a0 >= minLength) {
                    edges.add(FrameEdge(horizontal, (sum / hits).toInt(), a0, lastHit + 1, out))
                }
            }
        }
    }
    return edges
}

// --- confidence -----------------------------------------------------------------------------

/** Why a found panel is doubtful; each multiplies its confidence by [factor]. */
enum class PanelDoubt(val factor: Double) {
    /** Almost all paper inside: a blank frame or a caption box, not a panel. */
    BLANK_INSIDE(0.45),

    /** A gutter-colored band with solid lines both sides runs across it: two panels merged. */
    INNER_GUTTER(0.4),

    /** Little of its outline is ink: a fragment or a box cut through the gutter. */
    WEAK_BORDER(0.75),

    /** Over half the page while other panels were found. */
    OVERSIZED(0.75),
}

/** A page whose least trusted panel scores below this is looked at again (or kept whole). */
const val DOUBT_LIMIT = 0.5

/** The doubts about one [panel] (in [mask]'s pixels) among [panelCount] found on the page. */
fun panelDoubts(panel: PixelRect, mask: PaperMask, panelCount: Int, tuning: PanelTuning): List<PanelDoubt> {
    val doubts = mutableListOf<PanelDoubt>()
    if (paperShare(panel, mask) >= BLANK_PAPER_SHARE) doubts.add(PanelDoubt.BLANK_INSIDE)
    if (hasInnerGutter(panel, mask, tuning)) doubts.add(PanelDoubt.INNER_GUTTER)
    if (borderInkShare(panel, mask) < WEAK_BORDER_SHARE) doubts.add(PanelDoubt.WEAK_BORDER)
    if (panelCount >= 2 && panel.width.toLong() * panel.height > OVERSIZED_SHARE * mask.width * mask.height) {
        doubts.add(PanelDoubt.OVERSIZED)
    }
    return doubts
}

/** A panel's confidence, 0 to 1: the product of its doubts' factors. */
fun panelConfidence(doubts: List<PanelDoubt>): Double = doubts.fold(1.0) { c, d -> c * d.factor }

/** True when any of [panels] scores below [DOUBT_LIMIT]. */
fun isDoubtful(panels: List<PixelRect>, mask: PaperMask, tuning: PanelTuning): Boolean =
    panels.any { panelConfidence(panelDoubts(it, mask, panels.size, tuning)) < DOUBT_LIMIT }

private const val BLANK_PAPER_SHARE = 0.95
private const val WEAK_BORDER_SHARE = 0.5
private const val OVERSIZED_SHARE = 0.5

/** The share of [box] that is paper. */
fun paperShare(box: PixelRect, mask: PaperMask): Double {
    var paper = 0L
    for (y in box.top until box.bottom) for (x in box.left until box.right) if (mask.isPaper(x, y)) paper++
    return paper.toDouble() / max(1L, box.width.toLong() * box.height)
}

/**
 * The share of the line 2 px inside [box], all round, that is ink. Sides on the picture's
 * border are left out: a panel running off the page has no frame there.
 */
private fun borderInkShare(box: PixelRect, mask: PaperMask): Double {
    var ink = 0
    var total = 0
    val inset = 2
    fun count(x: Int, y: Int) {
        if (x !in 0 until mask.width || y !in 0 until mask.height) return
        total++
        if (mask.isInk(x, y)) ink++
    }
    if (box.top > 0) for (x in box.left until box.right) count(x, box.top + inset)
    if (box.bottom < mask.height) for (x in box.left until box.right) count(x, box.bottom - 1 - inset)
    if (box.left > 0) for (y in box.top until box.bottom) count(box.left + inset, y)
    if (box.right < mask.width) for (y in box.top until box.bottom) count(box.right - 1 - inset, y)
    return if (total == 0) 1.0 else ink.toDouble() / total
}

/**
 * True when a band of paper lines (each at least [PanelTuning.gutterShare] paper, as thick as a
 * gutter) crosses [box] away from its edges, with mostly-ink lines (border lines, at least
 * [PanelTuning.edgeShare]) just before and after it: the gutter between two merged panels.
 */
private fun hasInnerGutter(box: PixelRect, mask: PaperMask, tuning: PanelTuning): Boolean {
    val minGutter = max(2, (max(mask.width, mask.height) * tuning.minGutterShare).roundToInt())
    for (acrossRows in listOf(true, false)) {
        val start = if (acrossRows) box.top else box.left
        val end = if (acrossRows) box.bottom else box.right
        val length = if (acrossRows) box.width else box.height
        val margin = (tuning.minPanelShare * (if (acrossRows) mask.height else mask.width)).roundToInt()
        val paper = IntArray(end - start) { i ->
            var n = 0
            for (t in 0 until length) {
                val x = if (acrossRows) box.left + t else start + i
                val y = if (acrossRows) start + i else box.top + t
                if (mask.isPaper(x, y)) n++
            }
            n
        }
        fun solid(i: Int) = i in paper.indices && length - paper[i] >= tuning.edgeShare * length
        var runStart = -1
        for (i in 0..paper.size) {
            val blank = i < paper.size && paper[i] >= tuning.gutterShare * length
            if (blank && runStart < 0) runStart = i
            if (!blank && runStart >= 0) {
                val inside = runStart >= margin && paper.size - i >= margin
                val edges = (runStart - 3 until runStart).any(::solid) && (i until i + 3).any(::solid)
                if (inside && i - runStart >= minGutter && edges) return true
                runStart = -1
            }
        }
    }
    return false
}

// --- page furniture -------------------------------------------------------------------------

/**
 * True when [panels] (on [mask]'s picture) are really page furniture: a contents or credits page
 * whose one tall illustration strip holds nearly all the ink, beside a stack of sparse text
 * boxes. Ported from PanelsPlus (MIT; see THIRD_PARTY_NOTICES.md): at least six pieces, one
 * strip at least 90% of the page's height and 20–50% of its width, every other piece on the
 * other side of it; the strip holds at least 75% of the page's ink and is at least 60% ink;
 * the others together are under 30% ink.
 */
fun looksLikeFurnitureLayout(panels: List<PixelRect>, mask: PaperMask): Boolean {
    if (panels.size < 6) return false
    val pageInk = inkIn(PixelRect(0, 0, mask.width, mask.height), mask)
    if (pageInk == 0L) return false
    val strip = panels
        .filter { it.height >= 0.9 * mask.height && it.width >= 0.2 * mask.width && it.width <= 0.5 * mask.width }
        .maxByOrNull { it.width.toLong() * it.height } ?: return false
    val stripOnLeft = (strip.left + strip.right) / 2.0 < mask.width / 2.0
    val others = panels.filter { it != strip }
    val onOtherSide = others.all {
        if (stripOnLeft) it.left >= strip.left + strip.width * 0.9 else it.right <= strip.left + strip.width * 0.1
    }
    if (!onOtherSide) return false
    val stripInk = inkIn(strip, mask)
    if (stripInk < 0.75 * pageInk || stripInk < 0.6 * area(strip)) return false
    val otherInk = others.sumOf { inkIn(it, mask) }
    val otherArea = others.sumOf(::area)
    return otherArea > 0 && otherInk < 0.3 * otherArea
}

/**
 * True when the page is a text page (prose, an editorial, an ad): more than [MAX_OUTSIDE_INK] of
 * its ink lies outside every one of [panels], so cutting them out would drop the text.
 */
fun isMostlyTextOutside(panels: List<PixelRect>, mask: PaperMask): Boolean {
    val pageInk = inkIn(PixelRect(0, 0, mask.width, mask.height), mask)
    if (pageInk == 0L) return false
    var outside = 0L
    for (y in 0 until mask.height) {
        for (x in 0 until mask.width) {
            if (mask.isInk(x, y) && panels.none { x >= it.left && x < it.right && y >= it.top && y < it.bottom }) outside++
        }
    }
    return outside > MAX_OUTSIDE_INK * pageInk
}

private const val MAX_OUTSIDE_INK = 0.35

private fun inkIn(box: PixelRect, mask: PaperMask): Long {
    var ink = 0L
    for (y in max(0, box.top) until min(mask.height, box.bottom)) {
        for (x in max(0, box.left) until min(mask.width, box.right)) if (mask.isInk(x, y)) ink++
    }
    return ink
}

private fun area(r: PixelRect): Long = r.width.toLong() * r.height
