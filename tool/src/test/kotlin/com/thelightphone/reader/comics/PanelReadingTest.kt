package com.thelightphone.reader.comics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PanelReadingTest {

    // A 1000 × 2000 page on the 1080 × 1240 screen fits at 0.62 (shown 620 × 1240, centred).
    private val geometry = PageGeometry(1000, 2000, 1080, 1240)

    /** Where a page point lands on the screen at [zoom]. */
    private fun onScreen(zoom: PageZoom, x: Float, y: Float): Pair<Float, Float> =
        (geometry.pageLeft(zoom) + x * geometry.scale(zoom)) to (geometry.pageTop(zoom) + y * geometry.scale(zoom))

    @Test
    fun theModeDefaultsToPanels() {
        assertEquals(ComicReadingMode.PANELS, ComicReadingMode.fromSavedName(null))
        assertEquals(ComicReadingMode.PANELS, ComicReadingMode.fromSavedName("SIDEWAYS"))
        assertEquals(ComicReadingMode.FULL_PAGE, ComicReadingMode.fromSavedName("FULL_PAGE"))
    }

    @Test
    fun tapsWalkThroughThePanelsThenTheWholePage() {
        assertEquals(PanelStep.ToPanel(1), panelStep(forward = true, panel = 0, panelCount = 3))
        assertEquals(PanelStep.WholePageThenNext, panelStep(forward = true, panel = 2, panelCount = 3))
        assertEquals(PanelStep.ToPanel(1), panelStep(forward = false, panel = 2, panelCount = 3))
        assertEquals(PanelStep.PreviousPage, panelStep(forward = false, panel = 0, panelCount = 3))
    }

    @Test
    fun aPageWithNoPanelsTurnsAtTheNextTap() {
        assertEquals(PanelStep.NextPage, panelStep(forward = true, panel = -1, panelCount = 0))
        assertEquals(PanelStep.PreviousPage, panelStep(forward = false, panel = -1, panelCount = 0))
    }

    @Test
    fun aPageOpensOnItsFirstPanelGoingForwardAndItsLastGoingBack() {
        assertEquals(0, enteringPanel(4, forward = true))
        assertEquals(3, enteringPanel(4, forward = false))
        assertEquals(-1, enteringPanel(0, forward = true))
    }

    @Test
    fun theSavedPanelIsReopened() {
        assertEquals(0, openingPanelIndex(null, 5))
        assertEquals(0, openingPanelIndex(0, 5))
        assertEquals(2, openingPanelIndex(3, 5))
        assertEquals(4, openingPanelIndex(9, 5))
        assertEquals(-1, openingPanelIndex(3, 0))
        assertEquals(3, savedPanelNumber(2))
        assertEquals(0, savedPanelNumber(-1))
    }

    @Test
    fun aPanelFillsTheScreenWithAMarginAndIsCentred() {
        // A wide panel across the top: 1000 × 400 page pixels.
        val panel = PixelRect(0, 0, 1000, 400)
        val zoom = geometry.panelZoom(panel)
        val (left, top) = onScreen(zoom, 0f, 0f)
        val (right, bottom) = onScreen(zoom, 1000f, 400f)
        val margin = 1080 * PANEL_MARGIN_FRACTION
        // Width is the tight side: it spans the screen less the margins.
        assertEquals(margin, left, 0.5f)
        assertEquals(1080f - margin, right, 0.5f)
        // Centred up and down, even though the panel is at the page's top edge.
        assertEquals(620f, (top + bottom) / 2f, 0.5f)
    }

    @Test
    fun aTinyPanelIsZoomedNoMoreThanFourTimes() {
        assertEquals(MAX_ZOOM, geometry.panelZoom(PixelRect(500, 500, 520, 520)).zoom)
    }

    @Test
    fun theMoveBetweenPanelsStartsAndEndsExactly() {
        val from = geometry.panelZoom(PixelRect(0, 0, 500, 500))
        val to = geometry.panelZoom(PixelRect(500, 1000, 1000, 2000))
        assertEquals(from, geometry.between(from, to, 0f))
        assertEquals(to, geometry.between(from, to, 1f))
    }

    @Test
    fun halfwayTheCentreOfTheScreenIsHalfwayBetweenThePanels() {
        val from = geometry.panelZoom(PixelRect(0, 0, 500, 500)) // centred on (250, 250)
        val to = geometry.panelZoom(PixelRect(500, 1000, 1000, 2000)) // centred on (750, 1500)
        val half = geometry.between(from, to, 0.5f)
        val centreX = (540f - geometry.pageLeft(half)) / geometry.scale(half)
        val centreY = (620f - geometry.pageTop(half)) / geometry.scale(half)
        assertEquals(500f, centreX, 0.5f)
        assertEquals(875f, centreY, 0.5f)
        // The zoom lies between the two panels' zooms.
        assertTrue(half.zoom < from.zoom && half.zoom > to.zoom || half.zoom > from.zoom && half.zoom < to.zoom)
    }

    @Test
    fun zoomingOutToTheWholePageEndsFitted() {
        val from = geometry.panelZoom(PixelRect(0, 0, 500, 500))
        assertEquals(PageZoom(), geometry.between(from, PageZoom(), 1f))
    }
}
