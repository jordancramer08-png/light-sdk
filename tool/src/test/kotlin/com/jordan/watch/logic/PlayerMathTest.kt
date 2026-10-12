package com.jordan.watch.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerMathTest {

    @Test
    fun timeLabels() {
        assertEquals("0:00", PlayerMath.timeLabel(0))
        assertEquals("1:05", PlayerMath.timeLabel(65_000))
        assertEquals("1:02:05", PlayerMath.timeLabel(3_725_000))
        assertEquals("-1:00", PlayerMath.remainingLabel(60_000, 120_000))
        assertEquals("-0:00", PlayerMath.remainingLabel(130_000, 120_000))
    }

    @Test
    fun skipStaysInsideTheFile() {
        assertEquals(0L, PlayerMath.skip(5_000, -15_000, 100_000))
        assertEquals(100_000L, PlayerMath.skip(95_000, 15_000, 100_000))
        assertEquals(40_000L, PlayerMath.skip(25_000, 15_000, 0))
        assertEquals(15, PlayerMath.skipSeconds(7))
        assertEquals(30, PlayerMath.skipSeconds(30))
    }

    @Test
    fun scanSpeedsUpTheLongerItIsHeld() {
        assertEquals(5_000L, PlayerMath.scanStepMs(500))
        assertEquals(15_000L, PlayerMath.scanStepMs(3_000))
        assertEquals(60_000L, PlayerMath.scanStepMs(10_000))
    }

    @Test
    fun seekBarAndDoubleTap() {
        assertEquals(50_000L, PlayerMath.fractionToMs(0.5f, 100_000))
        assertEquals(0L, PlayerMath.fractionToMs(0.5f, 0))
        assertEquals(-1, PlayerMath.doubleTapDirection(10f, 100f))
        assertEquals(1, PlayerMath.doubleTapDirection(60f, 100f))
    }

    @Test
    fun startOrientation() {
        assertEquals(0, PlayerMath.startTurns("LANDSCAPE", "PORTRAIT", rotationAllowed = false))
        assertEquals(1, PlayerMath.startTurns("LANDSCAPE", "PORTRAIT", rotationAllowed = true))
        assertEquals(1, PlayerMath.startTurns("LAST", "LANDSCAPE", rotationAllowed = true))
        assertEquals(0, PlayerMath.startTurns("LAST", "PORTRAIT", rotationAllowed = true))
        assertEquals("LANDSCAPE", PlayerMath.orientationName(1))
    }

    @Test
    fun volumeText() {
        assertEquals("Volume off", PlayerMath.volumeText(0, 15))
        assertEquals("Volume 7 of 15", PlayerMath.volumeText(7, 15))
    }
}
