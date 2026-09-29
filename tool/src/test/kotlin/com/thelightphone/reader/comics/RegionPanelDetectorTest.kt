package com.thelightphone.reader.comics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegionPanelDetectorTest {

    // A 500 × 760 page with a 20 px margin and 3 rows of 2 panels, 16 px gutters.
    private val grid = listOf(
        rect(20, 20, 242, 250), rect(258, 20, 480, 250),
        rect(20, 266, 242, 494), rect(258, 266, 480, 494),
        rect(20, 510, 242, 740), rect(258, 510, 480, 740),
    )

    @Test
    fun findsABorderedGridInReadingOrder() {
        val page = SyntheticPage(500, 760, 255)
        grid.forEach { page.panel(it) }
        assertEquals(grid, findRegionPanels(page.gray(), gutter = 255))
    }

    @Test
    fun findsPanelsBetweenBlackGutters() {
        val page = SyntheticPage(500, 760, 0)
        grid.forEach { page.fill(it, 200) }
        assertEquals(grid, findRegionPanels(page.gray(), gutter = 0))
    }

    @Test
    fun aTiltedScanStillSplitsAndReadsRowByRow() {
        val page = SyntheticPage(500, 760, 255)
        grid.forEach { page.tiltedPanel(it, degrees = 1.5, cx = 250.0, cy = 380.0) }
        val found = findRegionPanels(page.gray(), gutter = 255)
        assertEquals(6, found.size, "$found")
        // Each found box is centred on its panel (turned about the page's centre), in grid order.
        found.zip(grid).forEach { (box, drawn) ->
            val (cx, cy) = turned((drawn.left + drawn.right) / 2.0, (drawn.top + drawn.bottom) / 2.0, 1.5)
            assertTrue(abs((box.left + box.right) / 2.0 - cx) < 4 && abs((box.top + box.bottom) / 2.0 - cy) < 4, "$box vs $drawn")
        }
    }

    @Test
    fun unevenRowsReadAcrossThenDown() {
        // No gutter runs right across: the left column splits at 300, the right one at 450.
        val page = SyntheticPage(500, 760, 255)
        val leftTop = rect(20, 20, 242, 300)
        val rightTop = rect(258, 20, 480, 450)
        val leftBottom = rect(20, 316, 242, 740)
        val rightBottom = rect(258, 466, 480, 740)
        listOf(leftTop, rightTop, leftBottom, rightBottom).forEach { page.panel(it) }
        assertEquals(listOf(leftTop, rightTop, leftBottom, rightBottom), findRegionPanels(page.gray(), gutter = 255))
    }

    @Test
    fun aTallPanelBesideTwoStackedOnesReadsLeftThenDown() {
        val page = SyntheticPage(500, 760, 255)
        val panels = listOf(rect(20, 20, 242, 740), rect(258, 20, 480, 372), rect(258, 388, 480, 740))
        panels.forEach { page.panel(it) }
        assertEquals(panels, findRegionPanels(page.gray(), gutter = 255))
    }

    @Test
    fun aBalloonCrossingAGutterIsCutBackIntoItsTwoPanels() {
        val page = SyntheticPage(500, 760, 255)
        grid.forEach { page.panel(it) }
        page.balloon(cx = 131, cy = 258, rx = 40, ry = 30) // across the gutter under the first panel
        val found = findRegionPanels(page.gray(), gutter = 255)
        assertEquals(6, found.size, "$found")
        found.zip(grid).forEach { (box, drawn) ->
            // Each panel holds its drawn box; the two the balloon joined keep up to half the gutter of it.
            assertTrue(box.left <= drawn.left && box.top <= drawn.top && box.right >= drawn.right && box.bottom >= drawn.bottom, "$box vs $drawn")
            assertTrue(drawn.top - box.top <= 8 && box.bottom - drawn.bottom <= 8, "$box vs $drawn")
        }
    }

    @Test
    fun aBalloonPokingIntoTheGutterStaysWithItsPanel() {
        val page = SyntheticPage(500, 760, 255)
        grid.forEach { page.panel(it) }
        page.balloon(cx = 200, cy = 245, rx = 30, ry = 12) // sticks 7 px out under the first panel
        val found = findRegionPanels(page.gray(), gutter = 255)
        assertEquals(listOf(rect(20, 20, 242, 258)) + grid.drop(1), found)
    }

    @Test
    fun aCaptionFloatingInAGutterJoinsTheNearestPanel() {
        val page = SyntheticPage(500, 760, 255)
        grid.forEach { page.panel(it) }
        page.fill(rect(60, 253, 180, 260), 0) // a caption strip 3 px under the first panel
        val found = findRegionPanels(page.gray(), gutter = 255)
        assertEquals(listOf(rect(20, 20, 242, 260)) + grid.drop(1), found)
    }

    @Test
    fun specksAndPageNumbersAreDropped() {
        val page = SyntheticPage(500, 760, 255)
        grid.forEach { page.panel(it) }
        page.fill(rect(247, 746, 253, 754), 0) // a page number under the last row
        page.fill(rect(5, 400, 7, 402), 0) // a speck in the margin
        assertEquals(grid, findRegionPanels(page.gray(), gutter = 255))
    }

    @Test
    fun borderlessPanelsSplitAlongWhiteGuttersReachingTheEdge() {
        val page = SyntheticPage(500, 760, 255)
        val panels = listOf(rect(0, 0, 500, 250), rect(0, 262, 244, 498), rect(256, 262, 500, 498), rect(0, 510, 500, 760))
        panels.forEach { page.fill(it, 90) }
        assertEquals(panels, findRegionPanels(page.gray(), gutter = 255))
    }

    @Test
    fun panelsInsideAFrameTheFloodCantEnterAreCutAlongTheirGutters() {
        val page = SyntheticPage(500, 760, 255)
        grid.forEach { page.panel(it) }
        // A frame line round the whole grid closes the gutters off from the page's edge.
        val frame = rect(14, 14, 486, 746)
        page.fill(rect(frame.left, frame.top, frame.right, frame.top + 2), 0)
        page.fill(rect(frame.left, frame.bottom - 2, frame.right, frame.bottom), 0)
        page.fill(rect(frame.left, frame.top, frame.left + 2, frame.bottom), 0)
        page.fill(rect(frame.right - 2, frame.top, frame.right, frame.bottom), 0)
        val found = findRegionPanels(page.gray(), gutter = 255)
        assertEquals(6, found.size, "$found")
        // Each box may take in the frame line beside it and half the gutter.
        found.zip(grid).forEach { (box, drawn) ->
            assertTrue(abs(box.left - drawn.left) <= 10 && abs(box.right - drawn.right) <= 10, "$box vs $drawn")
            assertTrue(abs(box.top - drawn.top) <= 10 && abs(box.bottom - drawn.bottom) <= 10, "$box vs $drawn")
        }
    }

    @Test
    fun figuresOnADarkCoverAreNotTakenForPanels() {
        // Light round shapes on black: the flood runs round them, but a circle only fills 79% of its box.
        val page = SyntheticPage(500, 760, 0)
        for ((cx, cy, r) in listOf(Triple(130, 200, 100), Triple(360, 330, 110), Triple(200, 560, 120))) {
            for (y in cy - r..cy + r) for (x in cx - r..cx + r) if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) page.set(x, y, 220)
        }
        assertEquals(emptyList(), findRegionPanels(page.gray(), gutter = 0))
    }

    @Test
    fun readingOrderGroupsTiltedRows() {
        // The right-hand panel of each row sits lower than the left one, but overlaps it.
        val a = rect(20, 30, 240, 250)
        val b = rect(260, 20, 480, 240)
        val c = rect(20, 280, 240, 500)
        val d = rect(260, 268, 480, 488)
        assertEquals(listOf(a, b, c, d), readingOrder(listOf(d, c, b, a)))
    }

    @Test
    fun readingOrderKeepsAColumnBesideATallPanelTogether() {
        val top = rect(20, 20, 480, 250)
        val left = rect(20, 266, 242, 494)
        val tall = rect(258, 266, 480, 740)
        val bottomLeft = rect(20, 510, 242, 740)
        assertEquals(listOf(top, left, bottomLeft, tall), readingOrder(listOf(tall, bottomLeft, left, top)))
    }

    /** Where ([x], [y]) goes when the page is turned [degrees] clockwise about its centre (250, 380). */
    private fun turned(x: Double, y: Double, degrees: Double): Pair<Double, Double> {
        val a = Math.toRadians(degrees)
        val dx = x - 250
        val dy = y - 380
        return (250 + dx * kotlin.math.cos(a) - dy * kotlin.math.sin(a)) to (380 + dx * kotlin.math.sin(a) + dy * kotlin.math.cos(a))
    }
}
