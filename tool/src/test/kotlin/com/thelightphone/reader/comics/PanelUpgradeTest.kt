package com.thelightphone.reader.comics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The detector upgrade (CLAUDE.md 12, "Panel checks"): one synthetic page per case. */
class PanelUpgradeTest {

    @Test
    fun aBalloonCrossingAGutterStaysWithThePanelHoldingMostOfIt() {
        // Two panels side by side, gutter 242..258; a balloon centred at x 232 (in the left panel) crosses it.
        val page = SyntheticPage(500, 760, 255)
        val left = rect(20, 20, 242, 740)
        val right = rect(258, 20, 480, 740)
        page.panel(left)
        page.panel(right)
        page.balloon(cx = 232, cy = 200, rx = 40, ry = 30)
        val found = findRegionPanels(page.gray(), gutter = 255)
        assertEquals(2, found.size, "$found")
        // The balloon's part in the gutter goes with the left panel: the cut is at the gutter's far side.
        assertTrue(found[0].right >= 256, "left ${found[0]}")
        assertTrue(found[1].left >= 256 && found[1].right == 480, "right ${found[1]}")
    }

    @Test
    fun aGridSkewedThreeDegreesSplitsAlongSlantedGutters() {
        // 2 × 3 panels, 40 px margin, 20 px gutters, all turned 3 degrees about the page's middle:
        // no straight gutter crosses the page, so the XY-cut needs its slanted lines.
        val page = SyntheticPage(540, 820, 255)
        val grid = listOf(
            rect(40, 40, 260, 280), rect(280, 40, 500, 280),
            rect(40, 300, 260, 520), rect(280, 300, 500, 520),
            rect(40, 540, 260, 780), rect(280, 540, 500, 780),
        )
        grid.forEach { page.tiltedPanel(it, degrees = 3.0, cx = 270.0, cy = 410.0) }
        val gray = page.gray()
        val found = cutPanels(gray, gutter = 255)
        assertEquals(6, found.size, "$found")
        assertTrue(isReliableSplit(found, gray))
        found.zip(grid).forEach { (box, drawn) ->
            val (cx, cy) = turnedAbout((drawn.left + drawn.right) / 2.0, (drawn.top + drawn.bottom) / 2.0, 3.0, 270.0, 410.0)
            assertTrue(abs((box.left + box.right) / 2.0 - cx) < 12 && abs((box.top + box.bottom) / 2.0 - cy) < 12, "$box vs $drawn")
        }
    }

    @Test
    fun aPanelBleedingOffThePageIsMergedBackByItsFrame() {
        // The top panel runs off the top and right edges: only its left and bottom frame lines are
        // drawn, and its light art lets the paper flood in round three figures.
        val page = SyntheticPage(500, 760, 255)
        page.fill(rect(20, 0, 23, 401), 0)
        page.fill(rect(20, 398, 500, 401), 0)
        page.fill(rect(80, 80, 150, 200), 60)
        page.fill(rect(220, 150, 300, 330), 60)
        page.fill(rect(360, 60, 440, 250), 60)
        val bottom = rect(20, 420, 480, 740)
        page.panel(bottom)
        val found = findRegionPanels(page.gray(), gutter = 255)
        assertEquals(2, found.size, "$found")
        assertEquals(rect(20, 0, 500, 401), found[0])
        assertEquals(bottom, found[1])
    }

    @Test
    fun aCaptionOutsideAPanelJoinsItButThePageNumberDoesNot() {
        val page = SyntheticPage(500, 760, 255)
        val top = rect(20, 20, 480, 330)
        val bottom = rect(20, 420, 480, 720)
        page.panel(top)
        page.panel(bottom)
        // A line of loose lettering 30 px under the top panel (too far for a balloon to join).
        for (i in 0 until 20) page.fill(rect(60 + i * 9, 360, 66 + i * 9, 369), 0)
        // The page number, in the bottom band.
        page.fill(rect(245, 742, 251, 751), 0)
        page.fill(rect(254, 742, 260, 751), 0)
        val found = findRegionPanels(page.gray(), gutter = 255)
        assertEquals(2, found.size, "$found")
        assertEquals(rect(20, 20, 480, 369), found[0])
        assertEquals(bottom, found[1])
    }

    @Test
    fun borderlessPanelsSplitAlongTheirDrawnBorders() {
        // One block of art inside a white margin, divided by black lines only: no blank gutter.
        val page = SyntheticPage(500, 760, 255)
        page.fill(rect(20, 20, 480, 740), 0)
        page.fill(rect(23, 23, 477, 378), 150)
        page.fill(rect(23, 382, 248, 737), 150)
        page.fill(rect(252, 382, 477, 737), 150)
        val look = analysePage(page.color())
        assertEquals(PanelMethod.BORDERS, look.method, "${look.panels} ${look.note}")
        assertEquals(3, look.panels.size, "${look.panels}")
        val (first, second, third) = look.panels
        assertTrue(first.top <= 24 && abs(first.bottom - 378) <= 3 && first.width > 440, "$first")
        assertTrue(abs(second.right - 248) <= 3 && abs(third.left - 252) <= 3, "$second $third")
    }

    @Test
    fun aCreditsPageIsKeptWhole() {
        // A tall illustration down the left, a stack of six framed boxes of sparse credits beside it.
        val page = SyntheticPage(500, 760, 255)
        page.panel(rect(20, 20, 200, 740), art = 60)
        for (i in 0 until 6) {
            val box = rect(220, 20 + i * 120, 480, 120 + i * 120)
            page.fill(box, 0)
            page.fill(rect(box.left + 2, box.top + 2, box.right - 2, box.bottom - 2), 255)
            for (j in 0 until 10) page.fill(rect(240 + j * 12, box.top + 40, 247 + j * 12, box.top + 50), 0)
        }
        val gray = page.gray()
        val pieces = findRegionPanels(gray, gutter = 255)
        assertEquals(7, pieces.size, "the stack splits into pieces: $pieces")
        assertTrue(looksLikeFurnitureLayout(pieces, PaperMask.of(gray, 255, PanelTuning())))
        val look = analysePage(page.color())
        assertEquals(emptyList(), look.panels)
        assertEquals("page furniture", look.note)
    }

    @Test
    fun aDoubtfulPanelIsScored() {
        // A box that is almost all paper inside (a blank frame) is doubted.
        val page = SyntheticPage(400, 400, 255)
        page.fill(rect(20, 20, 380, 380), 0)
        page.fill(rect(22, 22, 378, 378), 255)
        val doubts = panelDoubts(rect(20, 20, 380, 380), PaperMask.of(page.gray(), 255, PanelTuning()), 1, PanelTuning())
        assertTrue(PanelDoubt.BLANK_INSIDE in doubts, "$doubts")
        assertTrue(panelConfidence(doubts) < DOUBT_LIMIT)
    }

    @Test
    fun frameEdgesAreFollowedAcrossAOneDegreeDrift() {
        val page = SyntheticPage(400, 300, 255)
        // A line 2 px thick from (20, 100) to (380, 106): about 1 degree.
        for (x in 20 until 380) {
            val y = 100 + (x - 20) * 6 / 360
            page.fill(rect(x, y, x + 1, y + 2), 0)
        }
        val edges = frameEdges(PaperMask.of(page.gray(), 255, PanelTuning()), horizontal = true, minLength = 300)
        assertTrue(edges.any { it.out < 0 && it.lo <= 21 && it.hi >= 379 }, "$edges")
    }

    private fun turnedAbout(x: Double, y: Double, degrees: Double, cx: Double, cy: Double): Pair<Double, Double> {
        val a = Math.toRadians(degrees)
        val dx = x - cx
        val dy = y - cy
        return (cx + dx * kotlin.math.cos(a) - dy * kotlin.math.sin(a)) to (cy + dx * kotlin.math.sin(a) + dy * kotlin.math.cos(a))
    }
}
