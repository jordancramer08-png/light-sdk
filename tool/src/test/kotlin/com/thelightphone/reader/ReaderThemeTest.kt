package com.thelightphone.reader

import com.thelightphone.sdk.ui.LightThemeColors
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderThemeTest {

    @Test
    fun `there are four themes and dark is the default`() {
        assertEquals(listOf("Dark", "Light", "Sepia", "Night"), ReaderTheme.entries.map { it.label })
        assertEquals(ReaderTheme.DARK, ReaderTheme.DEFAULT)
    }

    @Test
    fun `dark keeps the look the reader always had`() {
        assertEquals(LightThemeColors.Dark, ReaderTheme.DARK.colors)
    }

    @Test
    fun `a saved name comes back as the same theme, anything else as dark`() {
        assertEquals(ReaderTheme.SEPIA, ReaderTheme.fromSavedName("SEPIA"))
        assertEquals(ReaderTheme.DARK, ReaderTheme.fromSavedName(null))
        assertEquals(ReaderTheme.DARK, ReaderTheme.fromSavedName("PURPLE"))
    }
}
