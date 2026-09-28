package com.thelightphone.reader

import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.BookProblem
import com.thelightphone.reader.data.ChapterMeta
import com.thelightphone.reader.data.ReadingPosition
import com.thelightphone.reader.data.SourceStamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryListTest {

    private fun book(
        fileName: String,
        author: String = "Someone",
        title: String = fileName,
        chapterChars: List<Int> = listOf(100),
        problem: BookProblem? = null,
    ) = BookMeta(
        slug = fileName.substringBefore('.'),
        title = title,
        author = author,
        chapters = chapterChars.mapIndexed { i, chars -> ChapterMeta(i + 1, "Chapter ${i + 1}", "x.txt", chars) },
        source = SourceStamp(fileName, 1, 1, 1),
        problem = problem,
    )

    private fun position(slug: String, chapter: Int, offset: Int) = ReadingPosition(slug, chapter, offset, 0)

    @Test
    fun sortsByAuthorThenFileName() {
        val books = listOf(
            book("b.epub", author = "Zafon"),
            book("Series 02.epub", author = "Adams"),
            book("Series 01.epub", author = "Adams"),
        )
        val order = libraryRows(books, emptyMap()).map { it.meta.source.fileName }
        assertEquals(listOf("Series 01.epub", "Series 02.epub", "b.epub"), order)
    }

    @Test
    fun authorSortIgnoresCaseAndAccents() {
        assertEquals(sortKey("Le Carre"), sortKey("le Carré"))
        val books = listOf(
            book("3.epub", author = "Le Carre"),
            book("1.epub", author = "Lewis"),
            book("2.epub", author = "le Carré"),
        )
        val order = libraryRows(books, emptyMap()).map { it.meta.source.fileName }
        assertEquals(listOf("2.epub", "3.epub", "1.epub"), order)
    }

    private fun orderOf(books: List<BookMeta>, sort: LibrarySort) =
        libraryRows(books, emptyMap(), sort).map { it.meta.source.fileName }

    @Test
    fun authorZtoAKeepsSeriesInOrderWithinAnAuthor() {
        val books = listOf(
            book("Series 02.epub", author = "Adams"),
            book("b.epub", author = "Zafon"),
            book("Series 01.epub", author = "Adams"),
        )
        assertEquals(listOf("b.epub", "Series 01.epub", "Series 02.epub"), orderOf(books, LibrarySort.AUTHOR_Z_TO_A))
    }

    @Test
    fun titleSortsIgnoreLeadingArticles() {
        val books = listOf(
            book("1.epub", title = "The Shadow of the Wind"),
            book("2.epub", title = "A Wizard of Earthsea"),
            book("3.epub", title = "An Echo"),
            book("4.epub", title = "Middlemarch"),
            book("5.epub", title = "Theology"),
        )
        // Echo, Middlemarch, Shadow, Theology, Wizard
        assertEquals(listOf("3.epub", "4.epub", "1.epub", "5.epub", "2.epub"), orderOf(books, LibrarySort.TITLE_A_TO_Z))
        assertEquals(listOf("2.epub", "5.epub", "1.epub", "4.epub", "3.epub"), orderOf(books, LibrarySort.TITLE_Z_TO_A))
    }

    @Test
    fun titleSortKeyOnlyDropsAWholeLeadingWord() {
        assertEquals("shadow of the wind", titleSortKey("The Shadow of the Wind"))
        assertEquals("theology", titleSortKey("Theology"))
        assertEquals("anathem", titleSortKey("Anathem"))
        assertEquals("etude in black", titleSortKey("An Étude in Black"))
    }

    @Test
    fun savedSortNameRoundTripsAndUnknownFallsBack() {
        LibrarySort.entries.forEach { assertEquals(it, LibrarySort.fromSavedName(it.name)) }
        assertEquals(LibrarySort.AUTHOR_A_TO_Z, LibrarySort.fromSavedName(null))
        assertEquals(LibrarySort.AUTHOR_A_TO_Z, LibrarySort.fromSavedName("SOMETHING_OLD"))
    }

    @Test
    fun notStartedWithoutSavedPlace() {
        assertEquals("Not started", progressText(book("a.epub"), null))
    }

    @Test
    fun progressCountsEarlierChaptersPlusOffset() {
        val meta = book("a.epub", chapterChars = listOf(100, 100, 200))
        // Chapters 1 and 2 done (200) + 50 into chapter 3 = 250 of 400.
        assertEquals("62% read", progressText(meta, position("a", chapter = 3, offset = 50)))
        assertEquals("0% read", progressText(meta, position("a", chapter = 1, offset = 0)))
    }

    @Test
    fun progressNeverPassesOneHundred() {
        val meta = book("a.epub", chapterChars = listOf(100))
        assertEquals("100% read", progressText(meta, position("a", chapter = 5, offset = 999)))
    }

    @Test
    fun problemBooksSayWhyAndCantOpen() {
        val drm = libraryRows(listOf(book("a.epub", problem = BookProblem.DRM, chapterChars = emptyList())), emptyMap()).single()
        assertEquals("Can't open (DRM)", drm.statusText)
        assertFalse(drm.canOpen)

        val broken = book("b.epub", problem = BookProblem.UNREADABLE, chapterChars = emptyList())
        assertEquals("Can't open", statusText(broken, null))
        assertTrue(libraryRows(listOf(book("c.epub")), emptyMap()).single().canOpen)
    }

    @Test
    fun onlyBooksWithProgressShowTheAccent() {
        val started = book("a.epub", chapterChars = listOf(100, 100))
        val fresh = book("b.epub")
        val drm = book("c.epub", problem = BookProblem.DRM, chapterChars = emptyList())
        val rows = libraryRows(listOf(started, fresh, drm), mapOf("a" to position("a", 2, 0)))
            .associateBy { it.meta.slug }
        assertEquals("50% read", rows.getValue("a").statusText)
        assertTrue(rows.getValue("a").isStarted)
        assertFalse(rows.getValue("b").isStarted)
        assertFalse(rows.getValue("c").isStarted)
    }

    @Test
    fun finishedBooksSayFinishedInsteadOfAPercentage() {
        val meta = book("a.epub", chapterChars = listOf(100, 100))
        val row = libraryRows(
            listOf(meta),
            mapOf("a" to position("a", 1, 50)),
            statuses = mapOf("a" to ReadingStatus.FINISHED),
        ).single()
        assertEquals("Finished", row.statusText)
        assertTrue(row.isStarted)
    }

    @Test
    fun finishedNeverHidesWhyABookCantOpen() {
        val drm = book("a.epub", problem = BookProblem.DRM, chapterChars = emptyList())
        assertEquals("Can't open (DRM)", statusText(drm, null, ReadingStatus.FINISHED))
    }

    @Test
    fun booksWithoutAStatusAreWantToRead() {
        val row = libraryRows(listOf(book("a.epub")), emptyMap()).single()
        assertEquals(ReadingStatus.WANT_TO_READ, row.status)
        assertEquals("Not started", row.statusText)
    }

    @Test
    fun filterKeepsOnlyMatchingBooksInSortOrder() {
        val books = listOf(
            book("c.epub", author = "Carr"),
            book("a.epub", author = "Adams"),
            book("b.epub", author = "Bell"),
            book("d.epub", author = "Dunn"),
        )
        val statuses = mapOf(
            "a" to ReadingStatus.READING,
            "b" to ReadingStatus.FINISHED,
            "c" to ReadingStatus.READING,
        )
        fun shown(filter: LibraryFilter) =
            libraryRows(books, emptyMap(), LibrarySort.AUTHOR_A_TO_Z, statuses, filter).map { it.meta.slug }

        assertEquals(listOf("a", "b", "c", "d"), shown(LibraryFilter.ALL))
        assertEquals(listOf("a", "c"), shown(LibraryFilter.READING))
        assertEquals(listOf("b"), shown(LibraryFilter.FINISHED))
        assertEquals(listOf("d"), shown(LibraryFilter.WANT_TO_READ))
    }
}
