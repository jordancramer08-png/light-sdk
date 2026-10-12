package com.thelightphone.sdk.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LightVideoPlayerTest {
    @Test
    fun trackIdRoundTrips() {
        assertEquals(2 to 1, parseTrackId("2:1"))
    }

    @Test
    fun badTrackIdIsNull() {
        assertNull(parseTrackId("2"))
        assertNull(parseTrackId("a:1"))
        assertNull(parseTrackId("1:2:3"))
    }
}
