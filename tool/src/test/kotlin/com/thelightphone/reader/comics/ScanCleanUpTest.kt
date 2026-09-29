package com.thelightphone.reader.comics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanCleanUpTest {

    private val yellowedLevels = PageLevels(20, 20, 20, 235, 220, 170)

    @Test
    fun theDarkestAndBrightestHalfPercentAreIgnored() {
        // 1000 pixels: 4 pure black specks and 4 pure white ones, the rest from 30 to 219.
        val argb = IntArray(1000) { i ->
            when {
                i < 4 -> argbOf(0, 0, 0)
                i < 8 -> argbOf(255, 255, 255)
                else -> (30 + i % 190).let { argbOf(it, it, it / 2) }
            }
        }
        val levels = measureLevels(ColorImage(100, 10, argb))
        assertEquals(PageLevels(30, 30, 15, 219, 219, 109), levels)
    }

    @Test
    fun onlyTheAreaAskedForIsMeasured() {
        val page = SyntheticPage(100, 100, 0)
        page.fill(rect(10, 10, 90, 90), 200)
        assertEquals(PageLevels(200, 200, 200, 200, 200, 200), measureLevels(page.color(), rect(10, 10, 90, 90)))
    }

    @Test
    fun autoTurnsYellowedPaperWhiteAndInkBlack() {
        val (red, green, blue) = channelStretches(yellowedLevels, CleanUp.AUTO)
        assertTrue(red.apply(235) >= 250 && green.apply(220) >= 250 && blue.apply(170) >= 250)
        assertTrue(red.apply(20) <= 5 && green.apply(20) <= 5 && blue.apply(20) <= 5)
    }

    @Test
    fun aCleanPageOnlyGetsTheMildContrast() {
        val (red, _, _) = channelStretches(PageLevels(0, 0, 0, 255, 255, 255), CleanUp.AUTO)
        assertEquals(128, red.apply(128))
        assertEquals(1.08f, red.gain, 0.001f)
        assertEquals(255, red.apply(250))
    }

    @Test
    fun aPageWithNoWhiteIsNotStretchedPastTheLimit() {
        // A red sky: green and blue never get light. Auto stretches them at most 1.8 times.
        val (red, green, blue) = channelStretches(PageLevels(10, 10, 10, 250, 60, 60), CleanUp.AUTO, withContrast = false)
        assertEquals(1.8f, green.gain, 0.001f)
        assertEquals(1.8f, blue.gain, 0.001f)
        assertTrue(red.gain < 1.1f)
    }

    @Test
    fun strongStretchesEachChannelFromItsOwnBlack() {
        val levels = PageLevels(40, 30, 60, 240, 230, 200)
        val (red, green, blue) = channelStretches(levels, CleanUp.STRONG, withContrast = false)
        assertEquals(0, red.apply(40))
        assertEquals(0, green.apply(30))
        assertEquals(0, blue.apply(60))
        assertEquals(255, blue.apply(200))
        // Auto shares the lowest black, so shadows keep their tint.
        val (autoRed, _, _) = channelStretches(levels, CleanUp.AUTO, withContrast = false)
        assertTrue(autoRed.apply(40) > 0)
    }

    @Test
    fun theMatrixHoldsEachChannelsStretch() {
        val matrix = cleanUpMatrix(yellowedLevels, CleanUp.STRONG)!!
        val (red, green, blue) = channelStretches(yellowedLevels, CleanUp.STRONG)
        assertEquals(20, matrix.size)
        assertEquals(red.gain, matrix[0])
        assertEquals(red.offset, matrix[4])
        assertEquals(green.gain, matrix[6])
        assertEquals(blue.offset, matrix[14])
        assertEquals(1f, matrix[18])
    }

    @Test
    fun offOrUnknownLevelsLeaveThePageAlone() {
        assertNull(cleanUpMatrix(yellowedLevels, CleanUp.OFF))
        assertNull(cleanUpMatrix(null, CleanUp.STRONG))
    }

    @Test
    fun yellowedGuttersComeOutWhiteForTheDetector() {
        val page = SyntheticPage(10, 10, 255)
        page.fill(rect(0, 0, 5, 10), 0)
        val color = page.color(::yellowed)
        val gray = leveledGray(color, measureLevels(color))
        assertTrue(gray[8, 5] >= 250, "paper ${gray[8, 5]}")
        assertTrue(gray[2, 5] <= 5, "ink ${gray[2, 5]}")
        // Without leveling, the paper is a dull 218.
        assertTrue(color.gray()[8, 5] < 220)
    }

    @Test
    fun brightColorsAreNotTakenForPaper() {
        val image = ColorImage(4, 1, intArrayOf(argbOf(255, 255, 255), argbOf(255, 230, 0), argbOf(0, 0, 0), argbOf(20, 20, 70)))
        val gray = leveledGray(image, measureLevels(image))
        assertEquals(255, gray[0, 0])
        assertEquals(0, gray[2, 0])
        // Yellow is as light as paper, but far from neutral: it comes out middle gray.
        assertTrue(gray[1, 0] in 100..160, "yellow ${gray[1, 0]}")
        // A deep navy gutter stays dark.
        assertTrue(gray[3, 0] < 40, "navy ${gray[3, 0]}")
    }

    @Test
    fun cleanUpDefaultsToAutoAndSteps() {
        assertEquals(CleanUp.AUTO, CleanUp.fromSavedName(null))
        assertEquals(CleanUp.AUTO, CleanUp.fromSavedName("SPARKLY"))
        assertEquals(CleanUp.STRONG, CleanUp.fromSavedName("STRONG"))
        assertNull(CleanUp.OFF.previous)
        assertEquals(CleanUp.STRONG, CleanUp.AUTO.next)
        assertNull(CleanUp.STRONG.next)
    }

    @Test
    fun shrinkingAColorPageAveragesEachChannel() {
        val image = ColorImage(4, 2, intArrayOf(
            argbOf(200, 0, 0), argbOf(0, 0, 0), argbOf(0, 0, 0), argbOf(0, 0, 100),
            argbOf(0, 100, 0), argbOf(0, 0, 0), argbOf(0, 0, 0), argbOf(0, 0, 100),
        ))
        val small = shrinkColorToLongSide(image, longSide = 2)
        assertEquals(argbOf(50, 25, 0), small[0, 0])
        assertEquals(argbOf(0, 0, 50), small[1, 0])
    }
}
