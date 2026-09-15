package com.thelightphone.bible.data

import kotlin.test.Test
import kotlin.test.assertEquals

// From bible/esv/psalms/119.txt.
private const val SAMPLE_CHAPTER = """
33|Teach me, O LORD, the way of Your statutes, / And I shall observe it to the end.
34|Give me understanding, that I may observe Your law / And keep it with all my heart.
35|Make me walk in the path of Your commandments,
"""

class VerseTest {

    @Test
    fun `parses verse number and text, skipping blank lines`() {
        val verses = parseVerses(SAMPLE_CHAPTER)

        assertEquals(3, verses.size)
        assertEquals(33, verses[0].number)
        assertEquals(
            "Teach me, O LORD, the way of Your statutes, / And I shall observe it to the end.",
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
