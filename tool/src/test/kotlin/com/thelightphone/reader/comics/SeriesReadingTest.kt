package com.thelightphone.reader.comics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeriesReadingTest {

    // A 2 × 2 grid on a 1000 × 1000 page.
    private val topLeft = PixelRect(0, 0, 490, 490)
    private val topRight = PixelRect(510, 0, 1000, 490)
    private val bottomLeft = PixelRect(0, 510, 490, 1000)
    private val bottomRight = PixelRect(510, 510, 1000, 1000)
    private val grid = listOf(topLeft, topRight, bottomLeft, bottomRight)

    @Test
    fun leftToRightReadsEachRowFromTheLeft() {
        assertEquals(grid, orderPanels(grid.reversed(), 1000, ReadingDirection.LEFT_TO_RIGHT, spread = false))
    }

    @Test
    fun rightToLeftReadsEachRowFromTheRight() {
        assertEquals(
            listOf(topRight, topLeft, bottomRight, bottomLeft),
            orderPanels(grid, 1000, ReadingDirection.RIGHT_TO_LEFT, spread = false),
        )
    }

    @Test
    fun aSpreadReadsOnePageThenTheOther() {
        // Two pages side by side, each with two stacked panels, on a 2000 × 1000 spread.
        val leftTop = PixelRect(0, 0, 980, 490)
        val leftBottom = PixelRect(0, 510, 980, 1000)
        val rightTop = PixelRect(1020, 0, 2000, 490)
        val rightBottom = PixelRect(1020, 510, 2000, 1000)
        val panels = listOf(leftTop, rightTop, leftBottom, rightBottom)
        assertEquals(
            listOf(leftTop, leftBottom, rightTop, rightBottom),
            orderPanels(panels, 2000, ReadingDirection.LEFT_TO_RIGHT, spread = true),
        )
        assertEquals(
            listOf(rightTop, rightBottom, leftTop, leftBottom),
            orderPanels(panels, 2000, ReadingDirection.RIGHT_TO_LEFT, spread = true),
        )
        // Not a spread: rows straight across both pages.
        assertEquals(panels, orderPanels(panels, 2000, ReadingDirection.LEFT_TO_RIGHT, spread = false))
    }

    @Test
    fun aPanelAcrossBothPagesKeepsTheSpreadOnePage() {
        val wide = PixelRect(0, 0, 2000, 490)
        val leftBottom = PixelRect(0, 510, 980, 1000)
        val rightBottom = PixelRect(1020, 510, 2000, 1000)
        assertEquals(
            listOf(wide, leftBottom, rightBottom),
            orderPanels(listOf(rightBottom, wide, leftBottom), 2000, ReadingDirection.LEFT_TO_RIGHT, spread = true),
        )
    }

    @Test
    fun spreadsFollowTheSettingUnlessSetByHand() {
        assertTrue(isSpreadPage(2000, 1000, SpreadSetting.AUTO, pageSaysSpread = null))
        assertFalse(isSpreadPage(1000, 1500, SpreadSetting.AUTO, pageSaysSpread = null))
        assertFalse(isSpreadPage(2000, 1000, SpreadSetting.OFF, pageSaysSpread = null))
        assertTrue(isSpreadPage(2000, 1000, SpreadSetting.OFF, pageSaysSpread = true))
        assertFalse(isSpreadPage(2000, 1000, SpreadSetting.AUTO, pageSaysSpread = false))
        assertTrue(isSpreadPage(1000, 1500, SpreadSetting.AUTO, pageSaysSpread = true))
    }

    @Test
    fun pageOverridesAreSavedAndReadBack() {
        val overrides = PageOverrides()
            .withSpread("p/01.jpg", true)
            .withSpread("p/02.jpg", false)
            .withWhole("p/03.jpg", true)
        assertEquals(setOf("spread:p/01.jpg", "single:p/02.jpg", "whole:p/03.jpg"), overrides.toSaved())
        assertEquals(overrides, PageOverrides.fromSaved(overrides.toSaved()))
        assertEquals(PageOverrides(), overrides.withSpread("p/01.jpg", null).withSpread("p/02.jpg", null).withWhole("p/03.jpg", false))
        assertEquals(PageOverrides(), PageOverrides.fromSaved(setOf("nonsense")))
        assertEquals(PageOverrides(), PageOverrides.fromSaved(null))
    }

    @Test
    fun savedNamesFallBackToTheDefaults() {
        assertEquals(ReadingDirection.RIGHT_TO_LEFT, ReadingDirection.fromSavedName("RIGHT_TO_LEFT"))
        assertEquals(ReadingDirection.LEFT_TO_RIGHT, ReadingDirection.fromSavedName("sideways"))
        assertEquals(SpreadSetting.AUTO, SpreadSetting.fromSavedName(null))
    }

    @Test
    fun rightToLeftTapsAreMirrored() {
        // Manga: the right 30% goes back, the rest forward.
        assertEquals(PageTap.PREVIOUS, pageTap(900f, 600f, 1080, 1240, zoomed = false, rightToLeft = true))
        assertEquals(PageTap.NEXT, pageTap(200f, 600f, 1080, 1240, zoomed = false, rightToLeft = true))
        assertEquals(PageTap.TOGGLE_OVERLAY, pageTap(200f, 100f, 1080, 1240, zoomed = false, rightToLeft = true))
    }

    @Test
    fun twoPagesAreKeptAheadWhenMemoryAllows() {
        assertEquals(setOf(4, 5, 6), pagesToKeep(4, 1, 30, ahead = 2))
        assertEquals(setOf(4, 3, 2), pagesToKeep(4, -1, 30, ahead = 2))
        assertEquals(setOf(28, 29), pagesToKeep(28, 1, 30, ahead = 2))
        assertEquals(2, pagesAhead(freeBytes = 200_000_000, roomNeeded = 100_000_000))
        assertEquals(1, pagesAhead(freeBytes = 50_000_000, roomNeeded = 100_000_000))
    }

    @Test
    fun theRemoveScriptsNoteIsRead() {
        val tab = '\t'
        val note = listOf(
            "comic${tab}Marvel/X-Men/01.cbz",
            "folder${tab}Marvel/X-Men",
            "",
            "nonsense",
            "folder$tab",
            "comic${tab}Marvel/X-Men/01.cbz",
        ).joinToString("\n")
        assertEquals(ForgetRequests(listOf("Marvel/X-Men/01.cbz"), listOf("Marvel/X-Men")), forgetRequests(note))
    }

    @Test
    fun seriesKeysOfAFolderAndTheFoldersUnderIt() {
        assertTrue(isSeriesKeyOf("comic_series:Marvel/X-Men:mode", "Marvel/X-Men"))
        assertTrue(isSeriesKeyOf("comic_series:Marvel/X-Men/Annuals:direction", "Marvel/X-Men"))
        assertTrue(isSeriesKeyOf("comic_clean_up:Marvel/X-Men", "Marvel/X-Men"))
        assertFalse(isSeriesKeyOf("comic_series:Marvel/X-Men 2:mode", "Marvel/X-Men"))
        assertFalse(isSeriesKeyOf("comic_clean_up:Marvel", "Marvel/X-Men"))
        assertFalse(isSeriesKeyOf("comic_reading_mode", "Marvel/X-Men"))
        assertFalse(isSeriesKeyOf("comic_series::mode", ""))
    }
}
