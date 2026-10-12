package com.jordan.watch.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageMathTest {

    private val gb = StorageMath.GB

    @Test
    fun `cap and free space both limit a send`() {
        assertTrue(StorageMath.fits(usedBytes = 45 * gb, addBytes = 5 * gb, freeBytes = 20 * gb))
        assertFalse(StorageMath.fits(usedBytes = 45 * gb, addBytes = 6 * gb, freeBytes = 20 * gb))
        assertFalse(StorageMath.fits(usedBytes = 0, addBytes = 6 * gb, freeBytes = 5 * gb))
        assertEquals(5 * gb, StorageMath.roomLeft(45 * gb))
        assertEquals(0, StorageMath.roomLeft(60 * gb))
    }

    @Test
    fun `size estimates`() {
        // 120 min at 2 Mbps = 1.8 GB; at 4 Mbps = 3.6 GB.
        assertEquals(1_800_000_000, StorageMath.estimateBytes(120, StorageMath.BPS_720P))
        assertEquals(3_600_000_000, StorageMath.estimateBytes(120, StorageMath.BPS_1080P))
    }

    @Test
    fun `totals and percent`() {
        val items = listOf(
            LibraryItem(id = "a", title = "A", bytes = 10 * gb),
            LibraryItem(id = "b", title = "B", bytes = null),
            LibraryItem(id = "c", title = "C", bytes = 15 * gb),
        )
        assertEquals(25 * gb, StorageMath.totalBytes(items))
        assertEquals(50, StorageMath.percentUsed(25 * gb))
        assertEquals(100, StorageMath.percentUsed(80 * gb))
    }

    @Test
    fun `bytes read nicely`() {
        assertEquals("2.1 GB", StorageMath.formatBytes(2_100_000_000))
        assertEquals("50.0 GB", StorageMath.formatBytes(50 * gb))
        assertEquals("734 MB", StorageMath.formatBytes(734_000_000))
        assertEquals("12 KB", StorageMath.formatBytes(12_345))
        assertEquals("0 KB", StorageMath.formatBytes(0))
    }

    @Test
    fun `quality line`() {
        assertEquals("1080p H.264 AAC · 2.1 GB", StorageMath.qualityLine(1080, "h264", "aac", 2_100_000_000))
        assertEquals("4K HEVC", StorageMath.qualityLine(2160, "hevc", null, null))
        assertEquals("734 MB", StorageMath.qualityLine(null, null, null, 734_000_000))
        assertEquals("", StorageMath.qualityLine(null, null, null, null))
    }
}
