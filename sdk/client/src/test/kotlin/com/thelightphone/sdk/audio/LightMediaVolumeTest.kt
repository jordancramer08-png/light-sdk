package com.thelightphone.sdk.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class LightMediaVolumeTest {
    @Test
    fun volumeLevelFractionIsStepOverMax() {
        assertEquals(0f, LightVolumeLevel(0, 15).fraction)
        assertEquals(0.4f, LightVolumeLevel(6, 15).fraction)
        assertEquals(1f, LightVolumeLevel(15, 15).fraction)
    }

    @Test
    fun volumeLevelFractionStaysInRange() {
        assertEquals(0f, LightVolumeLevel(3, 0).fraction)
        assertEquals(1f, LightVolumeLevel(20, 15).fraction)
    }
}
