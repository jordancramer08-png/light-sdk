package com.thelightphone.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderTextSizeTest {

    @Test
    fun `there are five sizes, growing from small to extra large`() {
        val sizes = ReaderTextSize.entries
        assertEquals(5, sizes.size)
        assertEquals(ReaderTextSize.SMALL, sizes.first())
        assertEquals(ReaderTextSize.EXTRA_LARGE, sizes.last())
        assertTrue(sizes.zipWithNext().all { (a, b) -> a.scale < b.scale })
    }

    @Test
    fun `medium keeps the size the reader always used`() {
        assertEquals(ReaderTextSize.MEDIUM, ReaderTextSize.DEFAULT)
        assertEquals(1.0f, ReaderTextSize.MEDIUM.scale)
    }

    @Test
    fun `minus and plus step one size and stop at the ends`() {
        assertEquals(ReaderTextSize.SMALL, ReaderTextSize.MEDIUM.smaller)
        assertEquals(ReaderTextSize.LARGE, ReaderTextSize.MEDIUM.larger)
        assertNull(ReaderTextSize.SMALL.smaller)
        assertNull(ReaderTextSize.EXTRA_LARGE.larger)
    }

    @Test
    fun `a saved name comes back as the same size, anything else as medium`() {
        assertEquals(ReaderTextSize.LARGER, ReaderTextSize.fromSavedName("LARGER"))
        assertEquals(ReaderTextSize.MEDIUM, ReaderTextSize.fromSavedName(null))
        assertEquals(ReaderTextSize.MEDIUM, ReaderTextSize.fromSavedName("HUGE"))
    }
}
