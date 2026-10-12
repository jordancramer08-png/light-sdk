package com.jordan.watch.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PosterMathTest {

    @Test
    fun `sample size keeps at least the target width`() {
        assertEquals(8, PosterMath.sampleSize(2000, 240))
        assertEquals(2, PosterMath.sampleSize(2000, 800))
        assertEquals(1, PosterMath.sampleSize(200, 240))
    }

    @Test
    fun `scaled size keeps the shape and never grows`() {
        assertEquals(240 to 360, PosterMath.scaledSize(500, 750, 240))
        assertEquals(200 to 300, PosterMath.scaledSize(200, 300, 240))
    }

    @Test
    fun `cache name changes when the file changes`() {
        val name = PosterMath.cacheName("/p.jpg", 10, 1, 240)
        assertEquals(name, PosterMath.cacheName("/p.jpg", 10, 1, 240))
        assertNotEquals(name, PosterMath.cacheName("/p.jpg", 11, 1, 240))
        assertNotEquals(name, PosterMath.cacheName("/p.jpg", 10, 2, 240))
        assertNotEquals(name, PosterMath.cacheName("/p.jpg", 10, 1, 800))
    }
}
