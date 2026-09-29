package com.thelightphone.reader.comics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComicViewTest {

    // A 1000 × 2000 page on the 1080 × 1240 screen fits at 0.62: shown 620 × 1240, centred.
    private val geometry = PageGeometry(1000, 2000, 1080, 1240)

    @Test
    fun tapsTurnPagesOrShowTheOverlay() {
        assertEquals(PageTap.TOGGLE_OVERLAY, pageTap(900f, 100f, 1080, 1240, zoomed = false))
        assertEquals(PageTap.PREVIOUS, pageTap(200f, 600f, 1080, 1240, zoomed = false))
        assertEquals(PageTap.NEXT, pageTap(700f, 600f, 1080, 1240, zoomed = false))
    }

    @Test
    fun whileZoomedOnlyTheOverlayTapWorks() {
        assertEquals(PageTap.NOTHING, pageTap(200f, 600f, 1080, 1240, zoomed = true))
        assertEquals(PageTap.NOTHING, pageTap(700f, 600f, 1080, 1240, zoomed = true))
        assertEquals(PageTap.TOGGLE_OVERLAY, pageTap(700f, 100f, 1080, 1240, zoomed = true))
    }

    @Test
    fun aPageFitsByItsTighterSide() {
        assertEquals(0.62f, fitScale(1000, 2000, 1080, 1240), 0.0001f)
        assertEquals(0.54f, fitScale(2000, 1000, 1080, 1240), 0.0001f)
        assertEquals(620f, geometry.shownWidth(PageZoom()), 0.01f)
        assertEquals(230f, geometry.pageLeft(PageZoom()), 0.01f)
        assertEquals(0f, geometry.pageTop(PageZoom()), 0.01f)
    }

    @Test
    fun zoomStaysBetweenFittedAndFourTimes() {
        assertEquals(4f, geometry.zoomBy(PageZoom(), 10f, 540f, 620f).zoom)
        assertEquals(1f, geometry.zoomBy(PageZoom(2f), 0.1f, 540f, 620f).zoom)
    }

    @Test
    fun zoomingKeepsTheSpotUnderTheFingers() {
        val before = PageZoom()
        val after = geometry.zoomBy(before, 2f, 500f, 300f)
        // The page point under (500, 300) before is still under it after.
        val pageX = (500f - geometry.pageLeft(before)) / geometry.scale(before)
        val pageY = (300f - geometry.pageTop(before)) / geometry.scale(before)
        assertEquals(500f, geometry.pageLeft(after) + pageX * geometry.scale(after), 0.5f)
        assertEquals(300f, geometry.pageTop(after) + pageY * geometry.scale(after), 0.5f)
    }

    @Test
    fun aFittedPageCannotBeMoved() {
        assertEquals(PageZoom(), geometry.panBy(PageZoom(), 300f, -200f))
    }

    @Test
    fun aZoomedPageMovesOnlyUntilItsEdgeMeetsTheScreen() {
        // At 2× the page is 1240 × 2480: 80 px spare each side across, 620 up and down.
        val moved = geometry.panBy(PageZoom(2f), 500f, -1000f)
        assertEquals(80f, moved.panX, 0.01f)
        assertEquals(-620f, moved.panY, 0.01f)
    }

    @Test
    fun doubleTapZoomsInThenFitsAgain() {
        val zoomed = geometry.doubleTap(PageZoom(), 540f, 620f)
        assertEquals(DOUBLE_TAP_ZOOM, zoomed.zoom)
        assertTrue(zoomed.isZoomed)
        val fitted = geometry.doubleTap(zoomed, 100f, 100f)
        assertEquals(PageZoom(), fitted)
        assertFalse(fitted.isZoomed)
    }

    @Test
    fun theVisibleRegionIsTheWholePageWhenFitted() {
        assertEquals(PixelRect(0, 0, 1000, 2000), geometry.visibleRegion(PageZoom()))
    }

    @Test
    fun theVisibleRegionShrinksWhenZoomed() {
        // At 2× centred: scale 1.24, page left edge at -80, top at -620.
        // (Rounding outward may add a pixel.)
        val region = geometry.visibleRegion(PageZoom(2f))
        assertEquals(64, region.left)
        assertEquals(936, region.right)
        assertEquals(500f, region.top.toFloat(), 1f)
        assertEquals(1500f, region.bottom.toFloat(), 1f)
    }

    @Test
    fun zoomedAreasDecodeAtFullDetailWhenThePageIsSmall() {
        assertEquals(1, geometry.regionSampleSize(PageZoom(2f)))
        // A huge 8000 × 16000 page fits at 0.0775; at 1.5× that is about 8.6 page pixels per screen pixel.
        val huge = PageGeometry(8000, 16000, 1080, 1240)
        assertEquals(8, huge.regionSampleSize(PageZoom(1.5f)))
    }

    @Test
    fun aPageIsDecodedNoBiggerThanTheScreenNeeds() {
        assertEquals(620 to 1240, fittedSize(1000, 2000, 1080, 1240))
        assertEquals(400 to 600, fittedSize(400, 600, 1080, 1240))
    }

    @Test
    fun aComicOpensOnItsSavedPage() {
        assertEquals(0, openingPageIndex(null, 30))
        assertEquals(11, openingPageIndex(12, 30))
        assertEquals(29, openingPageIndex(99, 30))
        assertEquals(0, openingPageIndex(0, 30))
    }

    @Test
    fun onlyTheShownPageAndTheNextInReadingDirectionAreKept() {
        assertEquals(setOf(4, 5), pagesToKeep(4, 1, 30))
        assertEquals(setOf(4, 3), pagesToKeep(4, -1, 30))
        assertEquals(setOf(29), pagesToKeep(29, 1, 30))
        assertEquals(setOf(0), pagesToKeep(0, -1, 30))
    }

    @Test
    fun theSliderMapsToPages() {
        assertEquals(0, sliderPage(0f, 30))
        assertEquals(29, sliderPage(1f, 30))
        assertEquals(15, sliderPage(0.51f, 30))
        assertEquals(0, sliderPage(0.7f, 1))
        assertEquals(1f, sliderFraction(29, 30))
        assertEquals(0f, sliderFraction(0, 1))
    }
}
