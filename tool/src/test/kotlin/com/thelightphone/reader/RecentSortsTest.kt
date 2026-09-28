package com.thelightphone.reader

import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.BookProblem
import com.thelightphone.reader.data.ChapterMeta
import com.thelightphone.reader.data.ReadingPosition
import com.thelightphone.reader.data.SourceStamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Recently read / Recently added, the Continue reading row, and the placeholder cover letter. */
class RecentSortsTest {

    private fun book(
        fileName: String,
        title: String = fileName,
        added: Long = 0,
        series: String? = null,
        number: String? = null,
        problem: BookProblem? = null,
    ) = BookMeta(
        slug = fileName.substringBefore('.'),
        title = title,
        author = "Someone",
        chapters = listOf(ChapterMeta(1, "Chapter 1", "001.txt", 100)),
        source = SourceStamp(fileName, 1, added, 1),
        problem = problem,
        series = series,
        seriesNumber = number,
    )

    /** Saved places opened at these times, by slug. */
    private fun readAt(vararg times: Pair<String, Long>): Map<String, ReadingPosition> =
        times.associate { (slug, at) -> slug to ReadingPosition(slug, 1, 50, at) }

    private fun slugs(rows: List<LibraryRow>) = rows.map { it.meta.slug }

    private fun shown(entries: List<LibraryEntry>) = entries.map {
        when (it) {
            is LibraryEntry.Book -> it.row.meta.slug
            is LibraryEntry.Series -> "series:" + it.name
        }
    }

    // --- Recently read ------------------------------------------------------------

    @Test
    fun recentlyReadPutsTheLastOpenedFirstAndNeverOpenedAfterByTitle() {
        val books = listOf(
            book("a.epub", title = "Zebra Days"),
            book("b.epub", title = "The Apple"),
            book("c.epub", title = "Middle"),
            book("d.epub", title = "Mango"),
        )
        val positions = readAt("c" to 100, "d" to 300)
        val order = libraryRows(books, positions, LibrarySort.RECENTLY_READ)
        // d (300), c (100), then never opened by title ignoring "The": Apple, Zebra.
        assertEquals(listOf("d", "c", "b", "a"), slugs(order))
    }

    @Test
    fun recentlyReadTiesFallBackToTitleThenFileName() {
        val books = listOf(book("b.epub", title = "Same"), book("a.epub", title = "Same"))
        assertEquals(listOf("a", "b"), slugs(libraryRows(books, readAt("a" to 5, "b" to 5), LibrarySort.RECENTLY_READ)))
    }

    // --- Recently added -----------------------------------------------------------

    @Test
    fun recentlyAddedPutsTheNewestFileFirst() {
        val books = listOf(
            book("old.epub", added = 1_000),
            book("new.epub", added = 3_000),
            book("mid.epub", added = 2_000),
        )
        assertEquals(listOf("new", "mid", "old"), slugs(libraryRows(books, emptyMap(), LibrarySort.RECENTLY_ADDED)))
    }

    @Test
    fun recentlyAddedTiesFallBackToFileName() {
        val books = listOf(book("b.epub", added = 7), book("a.epub", added = 7))
        assertEquals(listOf("a", "b"), slugs(libraryRows(books, emptyMap(), LibrarySort.RECENTLY_ADDED)))
    }

    @Test
    fun unknownSavedSortStillFallsBackButNewSortsRoundTrip() {
        assertEquals(LibrarySort.RECENTLY_READ, LibrarySort.fromSavedName("RECENTLY_READ"))
        assertEquals(LibrarySort.RECENTLY_ADDED, LibrarySort.fromSavedName("RECENTLY_ADDED"))
        assertEquals(LibrarySort.AUTHOR_A_TO_Z, LibrarySort.fromSavedName("NOPE"))
    }

    // --- series rows use their most recent book -----------------------------------

    private val one = book("s1.epub", title = "Saga One", added = 1_000, series = "Saga", number = "1")
    private val two = book("s2.epub", title = "Saga Two", added = 5_000, series = "Saga", number = "2")
    private val loner = book("loner.epub", title = "Loner", added = 3_000)

    @Test
    fun aSeriesSortsByItsMostRecentlyReadBook() {
        // Book 2 was read after Loner, book 1 before it.
        val positions = readAt("s1" to 10, "loner" to 20, "s2" to 30)
        val entries = libraryEntries(listOf(one, two, loner), positions, LibrarySort.RECENTLY_READ)
        assertEquals(listOf("series:Saga", "loner"), shown(entries))
        assertEquals(30L, entries.first().lastReadAt)
    }

    @Test
    fun aNeverOpenedSeriesSortsByItsNameAfterOpenedBooks() {
        val apple = book("apple.epub", title = "Apple")
        val entries = libraryEntries(listOf(one, two, loner, apple), readAt("loner" to 1), LibrarySort.RECENTLY_READ)
        assertEquals(listOf("loner", "apple", "series:Saga"), shown(entries))
    }

    @Test
    fun aSeriesSortsByItsNewestBookWhenRecentlyAdded() {
        val entries = libraryEntries(listOf(one, two, loner), emptyMap(), LibrarySort.RECENTLY_ADDED)
        // Book 2 (5,000) is newer than Loner (3,000), though book 1 (1,000) is older.
        assertEquals(listOf("series:Saga", "loner"), shown(entries))
        assertEquals(5_000L, entries.first().addedAt)
    }

    @Test
    fun aSeriesRowShowsItsFirstBooksCover() {
        val series = assertIs<LibraryEntry.Series>(libraryEntries(listOf(two, one), emptyMap()).single())
        assertEquals("s1", series.coverBook.slug)
    }

    // --- Continue reading ---------------------------------------------------------

    @Test
    fun continueReadingIsTheLastBookOpened() {
        val books = listOf(book("a.epub"), book("b.epub"), book("c.epub"))
        val row = continueReadingRow(books, readAt("a" to 100, "b" to 300, "c" to 200))
        assertEquals("b", row?.meta?.slug)
        assertEquals("50% read", row?.statusText)
    }

    @Test
    fun noContinueReadingWhenNothingWasOpened() {
        assertNull(continueReadingRow(listOf(book("a.epub")), emptyMap()))
    }

    @Test
    fun continueReadingIgnoresPlacesOfBooksNoLongerOnThePhone() {
        val row = continueReadingRow(listOf(book("a.epub")), readAt("a" to 1, "gone" to 99))
        assertEquals("a", row?.meta?.slug)
    }

    @Test
    fun continueReadingIsHiddenWhenTheFilterExcludesThatBook() {
        val books = listOf(book("a.epub"), book("b.epub"))
        val positions = readAt("a" to 100, "b" to 200)
        val statuses = mapOf("a" to ReadingStatus.READING, "b" to ReadingStatus.FINISHED)

        assertNull(continueReadingRow(books, positions, statuses, LibraryFilter.READING))
        assertEquals("b", continueReadingRow(books, positions, statuses, LibraryFilter.FINISHED)?.meta?.slug)
        assertEquals("b", continueReadingRow(books, positions, statuses, LibraryFilter.ALL)?.meta?.slug)
    }

    @Test
    fun continueReadingSkipsBooksThatCantOpen() {
        val books = listOf(book("a.epub"), book("drm.epub", problem = BookProblem.DRM))
        assertEquals("a", continueReadingRow(books, readAt("a" to 1, "drm" to 2))?.meta?.slug)
    }

    // --- placeholder letter -------------------------------------------------------

    @Test
    fun coverLetterIsTheTitlesFirstLetterOrDigit() {
        assertEquals("T", coverLetter("The Shadow of the Wind"))
        assertEquals("É", coverLetter("« émile »"))
        assertEquals("1", coverLetter("1984"))
        assertEquals("?", coverLetter("…"))
    }
}
