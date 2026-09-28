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
}
