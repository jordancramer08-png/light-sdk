package com.thelightphone.reader.pc

import com.thelightphone.reader.comics.ColorImage
import com.thelightphone.reader.comics.GrayImage
import com.thelightphone.reader.comics.PageLook
import com.thelightphone.reader.comics.PanelMethod
import com.thelightphone.reader.comics.PanelTuning
import com.thelightphone.reader.comics.PixelRect
import com.thelightphone.reader.comics.analysePage
import com.thelightphone.reader.comics.isGutterLevel
import com.thelightphone.reader.comics.leveledGray
import com.thelightphone.reader.comics.movedBy
import kotlin.math.max
import kotlin.math.min

/**
 * Finding a page's panels on the PC (CLAUDE.md 12, "PC comic tool"), more thoroughly than the
 * phone can afford to:
 *
 * 1. the phone's own analysis ([analysePage]: crop, levels, region detector, then XY-cut);
 * 2. if it finds nothing, the same again with looser settings, one after the other: gutters
 *    only 90% clear (skewed scans, panels whose corners touch the gutter), gutters further from
 *    the paper's gray (yellowed, uneven paper), then regions that fill less of their box;
 * 3. a page wider than tall (a two-page spread) whose whole still has no panels is looked at
 *    half by half, so each page's own gutters count; a half with no panels of its own is one
 *    panel (a splash), as long as the other half has some.
 *
 * Steps 2 and 3 are only tried on a page printed on light paper with light gutters (the old
 * scans they help), never on a comic's first page (its cover), and what they find must have at
 * least 3 panels and pass [isFramed]: every panel's edge must be at least half art, not gutter
 * (as a real panel's border is). Checked by eye on real comics: on borderless modern art,
 * covers and title pages the looser settings guessed wrong, so those keep the usual result. A
 * page that can't be split confidently is shown whole rather than given a bad guess.
 */
object PanelFinder {

    /** The looser settings tried in turn when the usual ones find nothing. */
    val FALLBACK_TUNINGS = listOf(
        PanelTuning(gutterShare = 0.9),
        PanelTuning(gutterTolerance = 80),
        PanelTuning(gutterShare = 0.9, minPanelFill = 0.7),
    )

    /** A panel found by the looser steps must have at least this share of its edge in art (for trying others: -Dpanels.framed=0.6). */
    val MIN_FRAMED_SHARE = System.getProperty("panels.framed")?.toDouble() ?: 0.5

    /** How the panels were found, for the report. */
    enum class How { USUAL, LOOSER, HALVES, NONE }

    class Found(val look: PageLook, val how: How)

    /**
     * Looks at one page: [small] is the page shrunk to about 800 px; [pageWidth] × [pageHeight]
     * its full size. [firstPage]: the comic's first page (its cover) gets the usual analysis only.
     */
    fun find(small: ColorImage, pageWidth: Int, pageHeight: Int, firstPage: Boolean = false): Found {
        val usual = analysePage(small, pageWidth, pageHeight)
        if (usual.panels.isNotEmpty()) return Found(usual, How.USUAL)
        if (firstPage || usual.gutter < LIGHT_GUTTER) return Found(usual, How.NONE)
        looser(small, pageWidth, pageHeight)?.let { return Found(it, How.LOOSER) }
        if (small.width > small.height * SPREAD_SHAPE) {
            halves(small, pageWidth, pageHeight, usual)?.let { return Found(it, How.HALVES) }
        }
        return Found(usual, How.NONE)
    }

    /** The looser steps are only for pages whose gutters are at least this light (paper). */
    private const val LIGHT_GUTTER = 160

    /** ... and only when they find at least this many panels. */
    private const val MIN_LOOSER_PANELS = 3

    /** A page this much wider than tall is looked at half by half. */
    private const val SPREAD_SHAPE = 1.15

    /** The first of the looser settings that finds framed panels; null when none does. */
    private fun looser(small: ColorImage, pageWidth: Int, pageHeight: Int): PageLook? {
        for (tuning in FALLBACK_TUNINGS) {
            val look = analysePage(small, pageWidth, pageHeight, tuning)
            if (look.panels.size >= MIN_LOOSER_PANELS && look.gutter >= LIGHT_GUTTER && isFramed(look, small, pageWidth, pageHeight, tuning)) return look
        }
        return null
    }

    private fun halves(small: ColorImage, pageWidth: Int, pageHeight: Int, whole: PageLook): PageLook? {
        val smallMiddle = small.width / 2
        val pageMiddle = (pageWidth.toLong() * smallMiddle / small.width).toInt()
        val left = PageImages.cut(small, PixelRect(0, 0, smallMiddle, small.height))
        val right = PageImages.cut(small, PixelRect(smallMiddle, 0, small.width, small.height))
        val leftPanels = panelsOfHalf(left, pageMiddle, pageHeight)
        val rightPanels = panelsOfHalf(right, pageWidth - pageMiddle, pageHeight).map { it.movedBy(pageMiddle, 0) }
        if (leftPanels.isEmpty() && rightPanels.isEmpty()) return null
        if (maxOf(leftPanels.size, 1) + maxOf(rightPanels.size, 1) < MIN_LOOSER_PANELS) return null
        val leftOrWhole = leftPanels.ifEmpty { listOf(PixelRect(0, 0, pageMiddle, pageHeight).inside(whole.crop)) }
        val rightOrWhole = rightPanels.ifEmpty { listOf(PixelRect(pageMiddle, 0, pageWidth, pageHeight).inside(whole.crop)) }
        return PageLook(whole.crop, whole.levels, leftOrWhole + rightOrWhole, PanelMethod.REGIONS, whole.gutter, emptyList())
    }

    /** One half's panels (in the half's own pixels): the usual settings, then the looser ones; all framed. */
    private fun panelsOfHalf(half: ColorImage, halfWidth: Int, height: Int): List<PixelRect> {
        for (tuning in listOf(PanelTuning()) + FALLBACK_TUNINGS) {
            val look = analysePage(half, halfWidth, height, tuning)
            if (look.panels.isNotEmpty() && look.gutter >= LIGHT_GUTTER && isFramed(look, half, halfWidth, height, tuning)) return look.panels
        }
        return emptyList()
    }

    /**
     * True when every panel's edge (a line just inside its box, all round) is at least
     * [MIN_FRAMED_SHARE] art rather than gutter color, on the page made small and leveled as
     * the detector saw it.
     */
    fun isFramed(look: PageLook, small: ColorImage, pageWidth: Int, pageHeight: Int, tuning: PanelTuning): Boolean {
        val gray = leveledGray(small, look.levels)
        val sx = small.width.toDouble() / pageWidth
        val sy = small.height.toDouble() / pageHeight
        return look.panels.all { panel ->
            val box = PixelRect(
                (panel.left * sx).toInt(), (panel.top * sy).toInt(),
                (panel.right * sx).toInt(), (panel.bottom * sy).toInt(),
            )
            framedShare(gray, box, look.gutter, tuning) >= MIN_FRAMED_SHARE
        }
    }

    /** The share of the box's edge, two pixels in, that isn't gutter color. */
    private fun framedShare(gray: GrayImage, box: PixelRect, gutter: Int, tuning: PanelTuning): Double {
        val inset = EDGE_INSET
        val left = (box.left + inset).coerceIn(0, gray.width - 1)
        val right = (box.right - 1 - inset).coerceIn(0, gray.width - 1)
        val top = (box.top + inset).coerceIn(0, gray.height - 1)
        val bottom = (box.bottom - 1 - inset).coerceIn(0, gray.height - 1)
        if (right <= left || bottom <= top) return 0.0
        var art = 0
        var all = 0
        fun look(x: Int, y: Int) {
            all++
            if (!isGutterLevel(gray[x, y], gutter, tuning)) art++
        }
        for (x in left..right) {
            look(x, top)
            look(x, bottom)
        }
        for (y in top + 1 until bottom) {
            look(left, y)
            look(right, y)
        }
        return art.toDouble() / max(1, all)
    }

    /** The edge is checked this many pixels inside the box (the box is rounded outward). */
    private const val EDGE_INSET = 2

    private fun PixelRect.inside(crop: PixelRect) = PixelRect(
        max(left, crop.left), max(top, crop.top), min(right, crop.right), min(bottom, crop.bottom),
    ).takeUnless { it.isEmpty } ?: this
}
