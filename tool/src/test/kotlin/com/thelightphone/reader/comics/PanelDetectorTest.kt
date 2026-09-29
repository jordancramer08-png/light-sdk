package com.thelightphone.reader.comics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PanelDetectorTest {

    /** A page drawn in gray levels: [background] everywhere, then each rectangle filled with art. */
    private class Page(val width: Int, val height: Int, background: Int) {
        val pixels = ByteArray(width * height) { background.toByte() }

        fun fill(rect: PixelRect, level: Int) {
            for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) set(x, y, level)
        }

        /** A panel: a black border 2 px wide round art in stripes of [art] and a little lighter. */
        fun panel(rect: PixelRect, art: Int = 90) {
            fill(rect, 0)
            val inside = PixelRect(rect.left + 2, rect.top + 2, rect.right - 2, rect.bottom - 2)
            for (y in inside.top until inside.bottom) {
                for (x in inside.left until inside.right) set(x, y, if ((x + y) % 7 == 0) art + 30 else art)
            }
        }

        fun set(x: Int, y: Int, level: Int) {
            pixels[y * width + x] = level.toByte()
        }

        fun image() = GrayImage(width, height, pixels)
    }

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = PixelRect(left, top, right, bottom)

    // A 500 × 760 page with a 20 px margin and 3 rows of 2 panels, 16 px gutters.
    private val grid = listOf(
        rect(20, 20, 242, 250), rect(258, 20, 480, 250),
        rect(20, 266, 242, 494), rect(258, 266, 480, 494),
        rect(20, 510, 242, 740), rect(258, 510, 480, 740),
    )

    @Test
    fun findsAGridOfPanelsInReadingOrder() {
        val page = Page(500, 760, 255)
        grid.forEach { page.panel(it) }
        assertEquals(grid, detectPanels(page.image()))
    }

    @Test
    fun findsPanelsBetweenBlackGutters() {
        val page = Page(500, 760, 0)
        grid.forEach { page.fill(it, 200) }
        assertEquals(grid, detectPanels(page.image()))
    }

    @Test
    fun yellowedPaperStillCountsAsGutter() {
        // Old scans: white page edges, but gutters of darker, yellowed paper.
        val page = Page(500, 760, 245)
        page.fill(rect(15, 15, 485, 745), 200)
        grid.forEach { page.panel(it) }
        assertEquals(grid, detectPanels(page.image()))
    }

    @Test
    fun aTallPanelBesideTwoStackedOnesReadsLeftThenDown() {
        val page = Page(500, 760, 255)
        val panels = listOf(rect(20, 20, 242, 740), rect(258, 20, 480, 372), rect(258, 388, 480, 740))
        panels.forEach { page.panel(it) }
        assertEquals(panels, detectPanels(page.image()))
    }

    @Test
    fun aSplashPageHasNoPanels() {
        val page = Page(500, 760, 255)
        page.panel(rect(0, 0, 500, 760))
        assertEquals(emptyList(), detectPanels(page.image()))
    }

    @Test
    fun oneFramedPictureHasNoPanels() {
        val page = Page(500, 760, 255)
        page.panel(rect(20, 20, 480, 740))
        assertEquals(emptyList(), detectPanels(page.image()))
    }

    @Test
    fun oneHugePanelMeansTheWholePage() {
        // A splash with a strip underneath: the big panel fills over 70% of the page.
        val page = Page(500, 760, 255)
        page.panel(rect(20, 20, 480, 620))
        page.panel(rect(20, 636, 480, 740))
        assertEquals(emptyList(), detectPanels(page.image()))
    }

    @Test
    fun panelsCoveringLittleOfThePageAreNotTrusted() {
        val page = Page(500, 760, 255)
        page.panel(rect(20, 20, 242, 250))
        page.panel(rect(258, 20, 480, 250))
        assertEquals(emptyList(), detectPanels(page.image()))
    }

    @Test
    fun aBalloonCrossingAGutterDoesNotJoinTheRows() {
        val page = Page(500, 760, 255)
        grid.forEach { page.panel(it) }
        // A balloon's outline crossing the gutter between the first two rows.
        for (y in 240..276) {
            page.set(100, y, 0)
            page.set(101, y, 0)
            page.set(160, y, 0)
        }
        assertEquals(grid, detectPanels(page.image()))
    }

    @Test
    fun aPageNumberInTheMarginIsDropped() {
        val page = Page(500, 760, 255)
        val panels = grid.take(4) + listOf(rect(20, 510, 242, 730), rect(258, 510, 480, 730))
        panels.forEach { page.panel(it) }
        page.fill(rect(247, 744, 253, 752), 0) // a small page number, centred under the gutter
        assertEquals(panels, detectPanels(page.image()))
    }

    @Test
    fun aThinCaptionStripJoinsTheNearestPanel() {
        val page = Page(500, 760, 255)
        page.panel(rect(20, 20, 480, 250))
        page.fill(rect(40, 256, 460, 276), 0) // a caption line, 20 px tall, close under the first row
        page.panel(rect(20, 300, 480, 520))
        page.panel(rect(20, 536, 480, 740))
        val panels = detectPanels(page.image())
        assertEquals(listOf(rect(20, 20, 480, 276), rect(20, 300, 480, 520), rect(20, 536, 480, 740)), panels)
    }

    @Test
    fun panelsRunningOffThePageStillSplitOnWhiteGutters() {
        // No margin: the border is all art, so white gutters are tried.
        val page = Page(500, 760, 255)
        val panels = listOf(
            rect(0, 0, 500, 250), rect(0, 262, 244, 498), rect(256, 262, 500, 498), rect(0, 510, 500, 760),
        )
        panels.forEach { page.fill(it, 90) }
        assertNull(gutterLevel(page.image()))
        assertEquals(panels, detectPanels(page.image()))
    }

    @Test
    fun panelsComeBackInThePagesOwnPixels() {
        val page = Page(500, 760, 255)
        grid.forEach { page.panel(it) }
        val panels = detectPanels(page.image(), pageWidth = 1000, pageHeight = 1520)
        assertEquals(rect(40, 40, 484, 500), panels.first())
        assertEquals(rect(516, 1020, 960, 1480), panels.last())
    }

    @Test
    fun aBigPageIsShrunkThenDetected() {
        val page = Page(1000, 1520, 255)
        grid.forEach { page.panel(rect(it.left * 2, it.top * 2, it.right * 2, it.bottom * 2)) }
        val small = shrinkToLongSide(page.image())
        assertEquals(526, small.width)
        assertEquals(800, small.height)
        val panels = detectPanels(small, 1000, 1520)
        assertEquals(6, panels.size)
        // Found on the small picture, so within a couple of page pixels of the drawn edges.
        panels.zip(grid).forEach { (found, drawn) ->
            assertTrue(kotlin.math.abs(found.left - drawn.left * 2) <= 3, "$found vs $drawn")
            assertTrue(kotlin.math.abs(found.bottom - drawn.bottom * 2) <= 3, "$found vs $drawn")
        }
    }

    @Test
    fun theGutterColorComesFromTheBorder() {
        assertEquals(255, gutterLevel(Page(100, 150, 255).image()))
        assertEquals(0, gutterLevel(Page(100, 150, 0).image()))
        assertNull(gutterLevel(Page(100, 150, 120).image()))
    }

    @Test
    fun shrinkingAveragesThePixels() {
        val page = Page(4, 2, 0)
        page.set(1, 0, 200)
        page.set(0, 1, 100)
        val small = shrinkToLongSide(page.image(), longSide = 2)
        assertEquals(2, small.width)
        assertEquals(1, small.height)
        assertEquals(75, small[0, 0])
        assertEquals(0, small[1, 0])
    }

    @Test
    fun colorsBecomeGrayByBrightness() {
        val gray = grayFromArgb(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt()), 4, 1)
        assertEquals(listOf(255, 0, 76, 149), (0 until 4).map { gray[it, 0] })
    }
}
