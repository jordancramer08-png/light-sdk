package com.thelightphone.reader.data

import kotlin.test.Test
import kotlin.test.assertEquals

class CoverSampleSizeTest {

    @Test
    fun shrinksByPowersOfTwoButNeverBelowTheWantedSize() {
        // 3000 × 4500 at 1/4 is 750 × 1125, still at least 480 × 720; 1/8 would be too small.
        assertEquals(4, sampleSize(3000, 4500, 480, 720))
    }

    @Test
    fun aSmallPictureIsDecodedAtFullSize() {
        assertEquals(1, sampleSize(400, 600, 480, 720))
        assertEquals(1, sampleSize(900, 1300, 480, 720))
    }

    @Test
    fun theShorterSideDecides() {
        // Wide picture: its height (720 at 1/2) is what stops the shrinking.
        assertEquals(2, sampleSize(4000, 1440, 480, 720))
    }
}
