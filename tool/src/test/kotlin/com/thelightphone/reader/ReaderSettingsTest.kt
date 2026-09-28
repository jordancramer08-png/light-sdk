package com.thelightphone.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderSettingsTest {

    @Test
    fun `the defaults keep the page the reader always had`() {
        val defaults = ReaderSettings()
        assertEquals(ReaderTextSize.MEDIUM, defaults.textSize)
        assertEquals(ReaderTypeface.LIGHT, defaults.typeface)
        assertEquals(ReaderLineSpacing.NORMAL, defaults.lineSpacing)
        assertEquals(1.45f, defaults.lineSpacing.multiplier)
        assertEquals(ReaderMargins.NORMAL, defaults.margins)
        assertEquals(1.5f, defaults.margins.gridUnits)
    }

    @Test
    fun `line spacing and margins grow from first to last`() {
        assertTrue(ReaderLineSpacing.entries.zipWithNext().all { (a, b) -> a.multiplier < b.multiplier })
        assertTrue(ReaderMargins.entries.zipWithNext().all { (a, b) -> a.gridUnits < b.gridUnits })
    }

    @Test
    fun `the buttons step one choice and stop at the ends`() {
        assertEquals(ReaderTypeface.SERIF, ReaderTypeface.LIGHT.next)
        assertNull(ReaderTypeface.LIGHT.previous)
        assertNull(ReaderTypeface.SANS.next)
        assertEquals(ReaderLineSpacing.COMPACT, ReaderLineSpacing.NORMAL.previous)
        assertNull(ReaderLineSpacing.RELAXED.next)
        assertEquals(ReaderMargins.WIDE, ReaderMargins.NORMAL.next)
        assertNull(ReaderMargins.NARROW.previous)
    }

    @Test
    fun `a saved name comes back as the same choice, anything else as the default`() {
        assertEquals(ReaderTypeface.SANS, ReaderTypeface.fromSavedName("SANS"))
        assertEquals(ReaderTypeface.LIGHT, ReaderTypeface.fromSavedName(null))
        assertEquals(ReaderLineSpacing.RELAXED, ReaderLineSpacing.fromSavedName("RELAXED"))
        assertEquals(ReaderLineSpacing.NORMAL, ReaderLineSpacing.fromSavedName("LOOSE"))
        assertEquals(ReaderMargins.NARROW, ReaderMargins.fromSavedName("NARROW"))
        assertEquals(ReaderMargins.NORMAL, ReaderMargins.fromSavedName(""))
    }
}
