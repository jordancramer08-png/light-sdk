package com.thelightphone.reader.comics

import kotlin.test.Test
import kotlin.test.assertEquals

class PageCropTest {

    /** Art that is never blank: stripes of gray. */
    private fun SyntheticPage.art(area: PixelRect) {
        for (y in area.top until area.bottom) for (x in area.left until area.right) set(x, y, if ((x + y) % 5 == 0) 30 else 140)
    }

    @Test
    fun aWhiteMarginIsCutLeavingASafetyMargin() {
        val page = SyntheticPage(500, 760, 250)
        page.art(rect(40, 50, 470, 720))
        // 1% of the shorter side (5 px) is given back round the content.
        assertEquals(rect(35, 45, 475, 725), findCrop(page.gray()))
    }

    @Test
    fun aBlackScannerBorderAndThePaperInsideItAreBothCut() {
        val page = SyntheticPage(500, 760, 10)
        page.fill(rect(20, 20, 480, 740), 245)
        page.art(rect(40, 40, 460, 720))
        assertEquals(rect(35, 35, 465, 725), findCrop(page.gray()))
    }

    @Test
    fun aScannerShadowDownTheSpineIsCut() {
        val page = SyntheticPage(500, 760, 245)
        // A shadow fading from dark at the left edge to paper, 30 px wide.
        for (x in 0 until 30) page.fill(rect(x, 0, x + 1, 760), 60 + x * 6)
        page.art(rect(50, 40, 460, 720))
        assertEquals(rect(45, 35, 465, 725), findCrop(page.gray()))
    }

    @Test
    fun neverMoreThanFifteenPercentGoesFromASide() {
        val page = SyntheticPage(500, 760, 255)
        page.art(rect(200, 300, 300, 460))
        val crop = findCrop(page.gray())
        // 15% of 500 is 75 px, of 760 is 114 px; the safety margin gives a little back.
        assertEquals(rect(70, 109, 430, 651), crop)
    }

    @Test
    fun artRunningToTheEdgeIsNotCut() {
        val page = SyntheticPage(500, 760, 255)
        page.art(rect(0, 0, 500, 760))
        assertEquals(rect(0, 0, 500, 760), findCrop(page.gray()))
    }

    @Test
    fun flatMidGrayArtAtTheEdgeIsNotTakenForABorder() {
        val page = SyntheticPage(500, 760, 150)
        page.art(rect(60, 60, 440, 700))
        assertEquals(rect(0, 0, 500, 760), findCrop(page.gray()))
    }

    @Test
    fun aCropOnTheSmallPictureGrowsOutwardToPagePixels() {
        val small = SyntheticPage(250, 380, 255).gray()
        assertEquals(rect(20, 40, 482, 722), cropToPage(rect(10, 20, 241, 361), small, 500, 760))
    }

    @Test
    fun panelsMoveIntoTheCropsOwnPixels() {
        val crop = rect(30, 40, 470, 720)
        assertEquals(rect(0, 10, 200, 210), rect(30, 50, 230, 250).within(crop))
        // A panel reaching past the crop is cut to it.
        assertEquals(rect(0, 0, 440, 680), rect(10, 0, 500, 760).within(crop))
    }

    @Test
    fun aCropIsKeptInsideThePage() {
        assertEquals(rect(0, 0, 500, 760), cropWithinPage(null, 500, 760))
        assertEquals(rect(0, 10, 500, 760), cropWithinPage(rect(-5, 10, 600, 800), 500, 760))
        assertEquals(rect(0, 0, 500, 760), cropWithinPage(rect(300, 300, 200, 200), 500, 760))
    }
}
