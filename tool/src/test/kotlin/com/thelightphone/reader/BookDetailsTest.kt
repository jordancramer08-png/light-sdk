package com.thelightphone.reader

import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.ChapterMeta
import com.thelightphone.reader.data.ReadingPosition
import com.thelightphone.reader.data.SourceStamp
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BookDetailsTest {

    /** Two chapters of 1,000 characters: 15,000 and 30,000 words. */
    private fun book(series: String? = null, seriesNumber: String? = null) = BookMeta(
        slug = "sea-story",
        title = "Sea Story",
        author = "Ann Author",
        chapters = listOf(
            ChapterMeta(1, "One", "001.txt", chars = 1_000, words = 15_000),
            ChapterMeta(2, "Two", "002.txt", chars = 1_000, words = 30_000),
        ),
        source = SourceStamp("Sea Story - Ann Author.epub", size = 1_572_864, modified = SEP_1_2026_NOON_UTC, parserVersion = 2),
        series = series,
        seriesNumber = seriesNumber,
    )

    private fun rows(meta: BookMeta, position: ReadingPosition?) =
        bookDetailRows(meta, position, Locale.US, ZoneOffset.UTC).associate { it.label to it.value }

    @Test
    fun notStartedBook() {
        val rows = rows(book(), position = null)

        assertEquals("2", rows["Chapters"])
        assertEquals("45,000", rows["Words"])
        assertEquals("About 3 h", rows["Reading time"])
        assertEquals("About 3 h", rows["Time left"])
        assertEquals("Not started", rows["Progress"])
        assertEquals("Sea Story - Ann Author.epub", rows["File"])
        assertEquals("1.5 MB", rows["File size"])
        assertEquals("Sep 1, 2026", rows["Added"])
        assertEquals("Not yet", rows["Last read"])
        assertNull(rows["Series"]) // no series, no row
    }

    @Test
    fun halfwayThroughTheFirstChapter() {
        val position = ReadingPosition("sea-story", chapterIndex = 1, charOffset = 500, updatedAt = SEP_1_2026_NOON_UTC + DAY)
        val rows = rows(book(), position)

        // 7,500 words left in chapter 1 + 30,000 in chapter 2 = 37,500 words = 150 min.
        assertEquals("About 2 h 30 min", rows["Time left"])
        assertEquals("25% read", rows["Progress"])
        assertEquals("Sep 2, 2026", rows["Last read"])
    }

    @Test
    fun rowsComeInOrderWithSeriesFirst() {
        val labels = bookDetailRows(book("Sea Tales", "2"), null, Locale.US, ZoneOffset.UTC).map { it.label }

        assertEquals(
            listOf("Series", "Chapters", "Words", "Reading time", "Time left", "Progress", "File", "File size", "Added", "Last read"),
            labels,
        )
    }

    @Test
    fun seriesWithAndWithoutNumber() {
        assertEquals("Sea Tales, book 2", seriesText(book("Sea Tales", "2")))
        assertEquals("Sea Tales", seriesText(book("Sea Tales", null)))
        assertNull(seriesText(book()))
    }

    @Test
    fun wordsLeftAtTheVeryEndIsNone() {
        val atEnd = ReadingPosition("sea-story", chapterIndex = 2, charOffset = 1_000, updatedAt = 0)

        assertEquals(0, wordsLeft(book(), atEnd))
        assertEquals("None", readingTimeText(0))
    }

    @Test
    fun readingTimeAt250WordsAMinute() {
        assertEquals("Under a minute", readingTimeText(100))
        assertEquals("About 1 min", readingTimeText(250))
        assertEquals("About 45 min", readingTimeText(11_250))
        assertEquals("About 1 h", readingTimeText(15_000))
        assertEquals("About 6 h 20 min", readingTimeText(95_000))
    }

    @Test
    fun fileSizes() {
        assertEquals("1 KB", fileSizeText(100))
        assertEquals("850 KB", fileSizeText(850 * 1024))
        assertEquals("1.2 MB", fileSizeText(1_258_291))
        assertEquals("24 MB", fileSizeText(24L * 1024 * 1024))
        assertEquals("1.4 GB", fileSizeText(1024L * 1024 * 1024 * 14 / 10))
    }

    private companion object {
        const val SEP_1_2026_NOON_UTC = 1_788_264_000_000L
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
