package com.thelightphone.reader.comics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageAnalysisTest {

    private val grid = listOf(
        rect(40, 40, 242, 250), rect(258, 40, 460, 250),
        rect(40, 266, 242, 494), rect(258, 266, 460, 494),
        rect(40, 510, 242, 720), rect(258, 510, 460, 720),
    )

    @Test
    fun aYellowedScanIsCroppedLeveledAndSplit() {
        // Yellowed paper, a scanner shadow down the left edge, a grid of panels.
        val page = SyntheticPage(500, 760, 255)
        for (x in 0 until 20) page.fill(rect(x, 0, x + 1, 760), 40 + x * 10)
        grid.forEach { page.panel(it) }
        val look = analysePage(page.color(::yellowed))
        assertEquals(rect(35, 35, 465, 725), look.crop)
        assertEquals(235, look.levels.whiteRed)
        assertEquals(170, look.levels.whiteBlue)
        assertEquals(PanelMethod.REGIONS, look.method)
        assertEquals(grid, look.panels)
    }

    @Test
    fun panelsAndCropComeBackInThePagesOwnPixels() {
        val page = SyntheticPage(500, 760, 255)
        grid.forEach { page.panel(it) }
        val look = analysePage(page.color(), pageWidth = 1000, pageHeight = 1520)
        assertEquals(rect(70, 70, 930, 1450), look.crop)
        assertEquals(rect(80, 80, 484, 500), look.panels.first())
        assertEquals(rect(516, 1020, 920, 1440), look.panels.last())
    }

    @Test
    fun theXyCutIsUsedWhenTheRegionsAreNotReliable() {
        // Panels of loose dots and no borders: the flood runs between the dots, so every dot is
        // a speck, but each panel's rows and columns still hold ink, so the XY-cut splits them.
        val page = SyntheticPage(500, 760, 255)
        for (panel in grid) {
            for (y in panel.top until panel.bottom) {
                for (x in panel.left until panel.right) if ((x * 7 + y * 13) % 10 == 0) page.set(x, y, 0)
            }
        }
        val look = analysePage(page.color())
        assertEquals(PanelMethod.XY_CUT, look.method)
        assertEquals(6, look.panels.size)
        look.panels.zip(grid).forEach { (found, drawn) ->
            assertTrue(found.left >= drawn.left - 1 && found.right <= drawn.right + 1, "$found vs $drawn")
        }
    }

    @Test
    fun aSplashHasNoPanels() {
        val page = SyntheticPage(500, 760, 255)
        page.panel(rect(20, 20, 480, 740))
        val look = analysePage(page.color())
        assertEquals(PanelMethod.NONE, look.method)
        assertEquals(emptyList(), look.panels)
        assertEquals(1, look.candidates.size)
    }
}
