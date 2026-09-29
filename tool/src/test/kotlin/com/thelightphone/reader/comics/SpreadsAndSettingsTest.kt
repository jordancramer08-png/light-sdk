package com.thelightphone.reader.comics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Comics part 5: two-page spreads, the viewer's settings, and reading the next panel ahead. */
class SpreadsAndSettingsTest {

    // A 3000 × 1500 spread as tall as the 1080 × 1240 screen: scale 0.8267, shown 2480 × 1240.
    private val spread = PageGeometry(3000, 1500, 1080, 1240, fitHeight = true)

    @Test
    fun onlyWidePagesInFullPageModeGetASpreadLayout() {
        assertEquals(PageLayout.WHOLE, pageLayout(1000, 1500, ComicReadingMode.FULL_PAGE, rotateSpreads = false))
        assertEquals(PageLayout.WHOLE, pageLayout(1000, 1500, ComicReadingMode.FULL_PAGE, rotateSpreads = true))
        assertEquals(PageLayout.WHOLE, pageLayout(3000, 1500, ComicReadingMode.PANELS, rotateSpreads = true))
        assertEquals(PageLayout.FIT_HEIGHT, pageLayout(3000, 1500, ComicReadingMode.FULL_PAGE, rotateSpreads = false))
        assertEquals(PageLayout.ROTATED, pageLayout(3000, 1500, ComicReadingMode.FULL_PAGE, rotateSpreads = true))
        // A square page isn't a spread.
        assertEquals(PageLayout.WHOLE, pageLayout(1500, 1500, ComicReadingMode.FULL_PAGE, rotateSpreads = false))
    }

    @Test
    fun aSpreadFillsTheScreenHeight() {
        val start = spread.startZoom(forward = true)
        assertEquals(1240f, spread.shownHeight(start), 0.5f)
        assertEquals(2480f, spread.shownWidth(start), 0.5f)
        assertEquals(2480 to 1240, fittedSize(3000, 1500, 1080, 1240, fitHeight = true))
    }

    @Test
    fun aSpreadOpensAtItsLeftEdgeGoingForwardAndItsRightEdgeGoingBack() {
        assertEquals(0f, spread.pageLeft(spread.startZoom(forward = true)), 0.5f)
        val back = spread.startZoom(forward = false)
        assertEquals(1080f, spread.pageLeft(back) + spread.shownWidth(back), 0.5f)
        // Not zoomed, so taps still turn pages.
        assertFalse(spread.startZoom(forward = true).isZoomed)
        // A page that fits whole just opens fitted.
        assertEquals(PageZoom(), PageGeometry(1000, 2000, 1080, 1240).startZoom(forward = true))
    }

    @Test
    fun aSpreadIsDraggedSidewaysOnlyUntilItsEdgeMeetsTheScreen() {
        val start = spread.startZoom(forward = true)
        val dragged = spread.panBy(start, -5000f, 300f)
        assertEquals(1080f, spread.pageLeft(dragged) + spread.shownWidth(dragged), 0.5f)
        assertEquals(0f, dragged.panY)
    }

    @Test
    fun doubleTapOnASpreadZoomsAtTheSpotTapped() {
        val start = spread.startZoom(forward = true)
        val before = (540f - spread.pageLeft(start)) / spread.scale(start)
        val zoomed = spread.doubleTap(start, 540f, 620f)
        val after = (540f - spread.pageLeft(zoomed)) / spread.scale(zoomed)
        assertTrue(zoomed.isZoomed)
        assertEquals(before, after, 0.5f)
    }

    @Test
    fun aPartOfATurnedSpreadMapsBackToThePage() {
        // Turned, the 3000 × 1500 page is 1500 wide and 3000 tall, its top on the left.
        assertEquals(PixelRect(0, 0, 3000, 1500), rotatedRegionInPage(PixelRect(0, 0, 1500, 3000), pageWidth = 3000))
        // The turned picture's top-left corner is the page's top-right corner.
        assertEquals(PixelRect(2800, 0, 3000, 100), rotatedRegionInPage(PixelRect(0, 0, 100, 200), pageWidth = 3000))
    }

    @Test
    fun settingsDefaultToNormalAndSmoothWithSpreadsUnturned() {
        val settings = ComicViewSettings()
        assertEquals(PanelMargin.NORMAL, settings.margin)
        assertEquals(PANEL_MARGIN_FRACTION, settings.margin.fraction)
        assertEquals(PanelTransition.SMOOTH, settings.transition)
        assertEquals(PANEL_MOVE_MS, settings.transition.moveMs)
        assertFalse(settings.rotateSpreads)
        assertEquals(PanelMargin.NORMAL, PanelMargin.fromSavedName("HUGE"))
        assertEquals(PanelTransition.SMOOTH, PanelTransition.fromSavedName(null))
        assertEquals(PanelTransition.OFF, PanelTransition.fromSavedName("OFF"))
    }

    @Test
    fun settingsStepWithinTheirRange() {
        assertNull(PanelMargin.TIGHT.previous)
        assertEquals(PanelMargin.ROOMY, PanelMargin.NORMAL.next)
        assertNull(PanelMargin.ROOMY.next)
        assertNull(PanelTransition.OFF.previous)
        assertEquals(PanelTransition.FAST, PanelTransition.OFF.next)
        assertEquals(0, PanelTransition.OFF.moveMs)
    }

    @Test
    fun aRoomierMarginLeavesMoreSpaceAroundThePanel() {
        val page = PageGeometry(1000, 2000, 1080, 1240)
        val panel = PixelRect(0, 0, 1000, 400)
        val normal = page.panelZoom(panel, PanelMargin.NORMAL.fraction)
        val roomy = page.panelZoom(panel, PanelMargin.ROOMY.fraction)
        assertTrue(roomy.zoom < normal.zoom)
        // The panel's left edge sits at the margin: 6% of the screen's 1080 px width.
        assertEquals(1080 * 0.06f, page.pageLeft(roomy), 0.5f)
    }

    @Test
    fun theNextPanelIsReadAheadThenTheNextPagesFirst() {
        assertEquals(PanelSpot(4, 2), nextPanelSpot(page = 4, panel = 1, panelCount = 5, nextPagePanels = 3))
        // On the last panel, the next tap ends on the next page's first panel.
        assertEquals(PanelSpot(5, 0), nextPanelSpot(page = 4, panel = 4, panelCount = 5, nextPagePanels = 3))
        // A page shown whole (no panels) goes to the next page's first panel too.
        assertEquals(PanelSpot(5, 0), nextPanelSpot(page = 4, panel = -1, panelCount = 0, nextPagePanels = 3))
        // Nothing to read ahead: the next page has no panels, isn't known yet, or there is none.
        assertNull(nextPanelSpot(page = 4, panel = 4, panelCount = 5, nextPagePanels = 0))
        assertNull(nextPanelSpot(page = 4, panel = 4, panelCount = 5, nextPagePanels = null))
    }
}
