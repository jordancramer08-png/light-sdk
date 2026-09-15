package com.thelightphone.bible.data

import kotlin.test.Test
import kotlin.test.assertEquals

// Synthetic fixture matching the converter's verse-line format (CLAUDE.md section 6) — not real scripture text.
private const val SAMPLE_CHAPTER = """
33|Sample verse text spanning a poetic line break, / continuing on the second line here.
34|Another sample verse showing the plain single-line case with no line break at all.
35|A third sample verse used only to exercise range filtering,
"""

class VerseTest {

    @Test
    fun `parses verse number and text, skipping blank lines`() {
        val verses = parseVerses(SAMPLE_CHAPTER)

        assertEquals(3, verses.size)
        assertEquals(33, verses[0].number)
        assertEquals(
            "Sample verse text spanning a poetic line break, / continuing on the second line here.",
            verses[0].text,
        )
        assertEquals(35, verses[2].number)
    }

    @Test
    fun `drops a line with no separator`() {
        val verses = parseVerses("not a verse line\n1|A real verse.")

        assertEquals(1, verses.size)
        assertEquals(1, verses[0].number)
    }

    @Test
    fun `filterVerseRange keeps only verses within the inclusive bounds`() {
        val verses = parseVerses(SAMPLE_CHAPTER)

        val ranged = filterVerseRange(verses, startVerse = 34, endVerse = 34)

        assertEquals(1, ranged.size)
        assertEquals(34, ranged[0].number)
    }

    @Test
    fun `filterVerseRange returns everything when bounds are absent`() {
        val verses = parseVerses(SAMPLE_CHAPTER)

        assertEquals(verses, filterVerseRange(verses, startVerse = null, endVerse = null))
    }
}
