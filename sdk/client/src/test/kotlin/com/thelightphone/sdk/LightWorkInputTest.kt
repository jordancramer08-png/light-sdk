package com.thelightphone.sdk

import kotlin.test.Test
import kotlin.test.assertEquals

class LightWorkInputTest {

    @Test
    fun `a job gets its input values, not key=value text`() {
        val stored = mapOf<String, Any?>("showId" to "abc123", "count" to 3, "missing" to null)
        assertEquals(mapOf("showId" to "abc123", "count" to "3"), stored.toJobInput())
    }
}
