package com.thelightphone.sdk

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LightNetworkTest {
    @Test
    fun `online only with a checked internet connection`() {
        assertTrue(isOnline(hasNetwork = true, hasInternet = true, validated = true))
        assertFalse(isOnline(hasNetwork = false, hasInternet = false, validated = false), "airplane mode")
        assertFalse(isOnline(hasNetwork = true, hasInternet = true, validated = false), "connected, but nothing gets through")
        assertFalse(isOnline(hasNetwork = true, hasInternet = false, validated = false), "a network without internet")
    }
}
