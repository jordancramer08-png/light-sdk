package com.thelightphone.adventure

import kotlin.test.Test
import kotlin.test.assertEquals

/** Proves tests in the tool module run on the laptop with no phone. Replace once engine tests exist. */
class LaptopTestSmokeTest {
    @Test
    fun runsOnTheLaptop() {
        assertEquals(4, 2 + 2)
    }
}
