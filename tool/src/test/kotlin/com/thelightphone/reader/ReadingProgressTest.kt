package com.thelightphone.reader

import com.thelightphone.reader.data.ChapterMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadingProgressTest {

    @Test
    fun `a page read in a normal time gives characters a minute`() {
        assertEquals(1_200f, pageSpeed(chars = 1_200, elapsedMs = 60_000))
        assertEquals(1_800f, pageSpeed(chars = 900, elapsedMs = 30_000))
    }

    @Test
    fun `turns under 2 seconds or over 5 minutes don't count`() {
        assertNull(pageSpeed(chars = 1_000, elapsedMs = 1_999))
        assertNull(pageSpeed(chars = 1_000, elapsedMs = 5 * 60_000 + 1))
        assertEquals(30_000f, pageSpeed(chars = 1_000, elapsedMs = 2_000))
        assertEquals(200f, pageSpeed(chars = 1_000, elapsedMs = 5 * 60_000))
    }

    @Test
    fun `an empty page doesn't count`() {
        assertNull(pageSpeed(chars = 0, elapsedMs = 30_000))
    }

    @Test
    fun `each timed page moves the average a tenth of the way`() {
        assertEquals(1_400f, DEFAULT_CHARS_PER_MINUTE)
        assertEquals(1_460f, updatedSpeed(1_400f, 2_000f), 0.01f)
        assertEquals(1_350f, updatedSpeed(1_400f, 900f), 0.01f)
    }

    @Test
    fun `the average settles on a steady reader's speed`() {
        var average = DEFAULT_CHARS_PER_MINUTE
        repeat(60) { average = updatedSpeed(average, 1_000f) }
        assertEquals(1_000f, average, 1f)
    }

    @Test
    fun `minutes left round to the nearest minute, at least one while anything is left`() {
        assertEquals(12, minutesLeft(chars = 16_800, charsPerMinute = 1_400f))
        assertEquals(1, minutesLeft(chars = 100, charsPerMinute = 1_400f))
        assertEquals(0, minutesLeft(chars = 0, charsPerMinute = 1_400f))
        assertEquals(2, minutesLeft(chars = 3_000, charsPerMinute = 1_400f))
    }

    @Test
    fun `time left in the chapter`() {
        assertEquals("12 min left in chapter", chapterTimeLeftText(12))
        assertEquals("1 min left in chapter", chapterTimeLeftText(1))
    }

    @Test
    fun `time left in the book shows hours and minutes`() {
        assertEquals("3 hr 20 min left in book", bookTimeLeftText(200))
        assertEquals("45 min left in book", bookTimeLeftText(45))
        assertEquals("2 hr left in book", bookTimeLeftText(120))
        assertEquals("1 hr 1 min left in book", bookTimeLeftText(61))
    }

    /** Four chapters of 10,000 characters. */
    private val chapters = (1..4).map { ChapterMeta(it, "Chapter $it", "00$it.txt", chars = 10_000) }

    @Test
    fun `progress counts the chapter, the percent and what is left`() {
        val progress = readingProgress(chapters, chapterIndex = 2, pageStart = 4_000, chapterLength = 10_000)
        assertEquals(ReadingProgress(2, 4, 35, charsLeftInChapter = 6_000, charsLeftInBook = 26_000), progress)
    }

    @Test
    fun `the progress line reads like the spec`() {
        val progress = ReadingProgress(5, 63, 34, charsLeftInChapter = 16_800, charsLeftInBook = 280_000)
        assertEquals(
            "Ch 5 of 63 · 34% · 12 min left in chapter",
            progressLineText(progress, 1_400f, TimeLeftMode.CHAPTER),
        )
        assertEquals(
            "Ch 5 of 63 · 34% · 3 hr 20 min left in book",
            progressLineText(progress, 1_400f, TimeLeftMode.BOOK),
        )
    }

    @Test
    fun `tapping switches between chapter and book`() {
        assertEquals(TimeLeftMode.BOOK, TimeLeftMode.CHAPTER.other)
        assertEquals(TimeLeftMode.CHAPTER, TimeLeftMode.BOOK.other)
    }
}
