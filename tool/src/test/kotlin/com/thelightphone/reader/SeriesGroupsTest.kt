package com.thelightphone.reader

import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.ChapterMeta
import com.thelightphone.reader.data.SourceStamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SeriesGroupsTest {

    private fun book(
        fileName: String,
        author: String = "Someone",
        title: String = fileName,
        series: String? = null,
        number: String? = null,
    ) = BookMeta(
        slug = fileName.substringBefore('.'),
        title = title,
        author = author,
        chapters = listOf(ChapterMeta(1, "Chapter 1", "001.txt", 100)),
        source = SourceStamp(fileName, 1, 1, 1),
        series = series,
        seriesNumber = number,
    )

    private val wind = book("wind.epub", "Zafon", "The Shadow of the Wind", "Cemetery of Forgotten Books", "1")
    private val angel = book("angel.epub", "Zafon", "The Angel's Game", "Cemetery of Forgotten Books", "2")
    private val prisoner = book("prisoner.epub", "Zafon", "The Prisoner of Heaven", "Cemetery of Forgotten Books", "3")
    private val emma = book("emma.epub", "Austen", "Emma")

    /** Book rows show their file name, series rows "series:<name>". */
    private fun shown(entries: List<LibraryEntry>) = entries.map {
        when (it) {
            is LibraryEntry.Book -> it.row.meta.source.fileName
            is LibraryEntry.Series -> "series:" + it.name
        }
    }

    @Test
    fun aSeriesBecomesOneRowWithItsBooksInNumberOrder() {
        val entries = libraryEntries(listOf(prisoner, emma, wind, angel), emptyMap())
        assertEquals(listOf("emma.epub", "series:Cemetery of Forgotten Books"), shown(entries))
        val series = assertIs<LibraryEntry.Series>(entries[1])
        assertEquals(listOf("wind.epub", "angel.epub", "prisoner.epub"), series.rows.map { it.meta.source.fileName })
        assertEquals("Zafon", series.author)
    }

    @Test
    fun countsBooksAndFinished() {
        val statuses = mapOf("wind" to ReadingStatus.FINISHED, "angel" to ReadingStatus.READING)
        val series = libraryEntries(listOf(wind, angel, prisoner), emptyMap(), statuses = statuses).single()
        assertEquals("3 books · 1 finished", assertIs<LibraryEntry.Series>(series).countText)
    }

    @Test
    fun aSeriesWithOneBookOnThePhoneIsANormalRow() {
        val entries = libraryEntries(listOf(wind, emma), emptyMap())
        assertEquals(listOf("emma.epub", "wind.epub"), shown(entries))
    }

    @Test
    fun groupingOffShowsEveryBook() {
        val entries = libraryEntries(listOf(wind, angel, emma), emptyMap(), groupSeries = false)
        assertEquals(listOf("emma.epub", "angel.epub", "wind.epub"), shown(entries))
    }

    @Test
    fun seriesNamesMatchIgnoringCaseAndAccents() {
        val a = book("a.epub", series = "Les Misérables", number = "1")
        val b = book("b.epub", series = "les miserables", number = "2")
        assertEquals(1, libraryEntries(listOf(a, b), emptyMap()).size)
    }

    @Test
    fun numbersSortAsNumbersAndMissingOnesGoLast() {
        val books = listOf(
            book("x.epub", series = "S"),
            book("ten.epub", series = "S", number = "10"),
            book("two.epub", series = "S", number = "2"),
            book("half.epub", series = "S", number = "2.5"),
        )
        val series = assertIs<LibraryEntry.Series>(libraryEntries(books, emptyMap()).single())
        assertEquals(listOf("two.epub", "half.epub", "ten.epub", "x.epub"), series.rows.map { it.meta.source.fileName })
    }

    @Test
    fun titleSortPlacesTheSeriesByItsName() {
        val dune = book("dune.epub", title = "Dune")
        val zoo = book("zoo.epub", title = "Zoo")
        val entries = libraryEntries(listOf(zoo, wind, angel, dune), emptyMap(), LibrarySort.TITLE_A_TO_Z)
        assertEquals(listOf("series:Cemetery of Forgotten Books", "dune.epub", "zoo.epub"), shown(entries))
    }

    @Test
    fun theFilterIsAppliedBeforeGrouping() {
        val statuses = mapOf("wind" to ReadingStatus.FINISHED, "angel" to ReadingStatus.FINISHED)
        val finished = libraryEntries(
            listOf(wind, angel, prisoner),
            emptyMap(),
            statuses = statuses,
            filter = LibraryFilter.FINISHED,
        )
        assertEquals("2 books · 2 finished", assertIs<LibraryEntry.Series>(finished.single()).countText)

        // Only one book is Want to Read, so it shows as a normal row.
        val wantToRead = libraryEntries(
            listOf(wind, angel, prisoner),
            emptyMap(),
            statuses = statuses,
            filter = LibraryFilter.WANT_TO_READ,
        )
        assertEquals(listOf("prisoner.epub"), shown(wantToRead))
    }
}
