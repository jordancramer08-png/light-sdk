package com.thelightphone.reader.comics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The view planner (CLAUDE.md 12, "Views") on the phone's 1080 × 1240 screen, Normal margin. */
class ViewPlannerTest {

    private val page = PixelRect(0, 0, 1000, 1500)

    private fun plan(panels: List<PlannedPanel>, crop: PixelRect = page, group: Boolean = true, doubtful: Boolean = false) =
        planViews(panels, crop, 1080, 1240, PANEL_MARGIN_FRACTION, group, doubtful)

    // A row of four tiny strip panels, then one big panel under them.
    private val strip = listOf(
        PixelRect(50, 100, 200, 300), PixelRect(250, 100, 400, 300),
        PixelRect(450, 100, 600, 300), PixelRect(650, 100, 800, 300),
    )
    private val big = PixelRect(50, 400, 950, 1400)

    @Test
    fun tinyStripPanelsAreGroupedWhileStillReadable() {
        val views = plan((strip + big).map { PlannedPanel(it) })
        // Three fit together at over 2x; the fourth would make them too small, so it's its own view.
        assertEquals(listOf(PixelRect(50, 100, 600, 300), strip[3], big), views)
    }

    @Test
    fun groupingOffKeepsEveryPanel() {
        assertEquals(strip + big, plan((strip + big).map { PlannedPanel(it) }, group = false))
    }

    @Test
    fun panelsInDifferentRowsAreNeverGrouped() {
        val stacked = listOf(PixelRect(50, 100, 200, 300), PixelRect(50, 400, 200, 600))
        assertEquals(stacked, plan(stacked.map { PlannedPanel(it) }))
    }

    @Test
    fun aTallSplashIsShownInThreePartsCutAtTheEmptiestRows() {
        val tall = PixelRect(300, 50, 700, 1450)
        // Ink everywhere, an empty band at slice 10, and a balloon around slice 16 whose gap
        // between two lines of lettering (slice 16 itself) is empty: the cut must take slice 10.
        val ink = MutableList(INK_SLICES) { 300 }
        ink[10] = 0
        ink[15] = 900
        ink[16] = 0
        ink[17] = 900
        val views = plan(listOf(PlannedPanel(tall, ink)))
        assertEquals(3, views.size, "$views")
        assertEquals(50 + 306, views[0].bottom)
        assertEquals(views[0].bottom, views[1].top)
        assertEquals(views[1].bottom, views[2].top)
        assertEquals(1450, views[2].bottom)
        assertTrue(views.all { it.left == 300 && it.right == 700 })
    }

    @Test
    fun aWidePanoramaIsShownInThreePartsAcross() {
        val spread = PixelRect(0, 0, 1600, 1000)
        val panorama = PixelRect(0, 300, 1600, 700)
        val views = plan(listOf(PlannedPanel(panorama)), crop = spread)
        assertEquals(listOf(PixelRect(0, 300, 533, 700), PixelRect(533, 300, 1067, 700), PixelRect(1067, 300, 1600, 700)), views)
    }

    @Test
    fun aSomewhatTallPanelIsDividedInTwo() {
        val views = plan(listOf(PlannedPanel(PixelRect(250, 50, 750, 1450))))
        assertEquals(listOf(PixelRect(250, 50, 750, 750), PixelRect(250, 750, 750, 1450)), views)
    }

    @Test
    fun aPanelOfTheScreensShapeIsLeftWhole() {
        assertEquals(listOf(big), plan(listOf(PlannedPanel(big))))
    }

    @Test
    fun aDoubtfulPageShowsTheWholePageFirst() {
        val views = plan(listOf(PlannedPanel(big)), doubtful = true)
        assertEquals(listOf(page, big), views)
    }

    @Test
    fun noPanelsMeansNoViews() {
        assertEquals(emptyList(), plan(emptyList(), doubtful = true))
    }

    @Test
    fun inkIsMeasuredAlongTheLongerSide() {
        // A 40 × 96 page: the top half black, the bottom half white.
        val pixels = ByteArray(40 * 96) { if (it / 40 < 48) 0 else 255.toByte() }
        val ink = panelInk(GrayImage(40, 96, pixels), 40, 96, PixelRect(0, 0, 40, 96))
        assertEquals(INK_SLICES, ink.size)
        assertEquals(1000, ink.first())
        assertEquals(0, ink.last())
    }
}
