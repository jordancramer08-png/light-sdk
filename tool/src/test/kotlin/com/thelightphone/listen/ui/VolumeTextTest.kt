package com.thelightphone.listen.ui

import com.thelightphone.sdk.audio.LightVolumeLevel
import kotlin.test.Test
import kotlin.test.assertEquals

class VolumeTextTest {

    @Test
    fun `volume shows the step out of the maximum, or off`() {
        assertEquals("Volume 7 of 15", volumeText(LightVolumeLevel(7, 15)))
        assertEquals("Volume 15 of 15", volumeText(LightVolumeLevel(15, 15)))
        assertEquals("Volume off", volumeText(LightVolumeLevel(0, 15)))
    }
}
