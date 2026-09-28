package com.thelightphone.reader.data

import com.thelightphone.reader.epub.EpubFixture
import com.thelightphone.reader.epub.EpubParser
import com.thelightphone.reader.epub.PARSER_VERSION
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryStoreTest {

    private val root: File = Files.createTempDirectory("librarystore").toFile()
    private val booksDir = File(root, "shared/books").apply { mkdirs() }
    private val libraryDir = File(root, "library")

    /** How many times the EPUB parser actually ran. */
    private var parseCount = 0

    private fun store(parserVersion: Int = PARSER_VERSION) = LibraryStore(
        booksDir = booksDir,
        libraryDir = libraryDir,
        parse = { file: File -> parseCount++; EpubParser.parse(file) },
        parserVersion = parserVersion,
    )

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** Puts a small EPUB into shared/books under [fileName]. */
    private fun addBook(fileName: String, title: String, chapterCount: Int = 2, drm: Boolean = false): File {
        val chapters = (1..chapterCount).map {
            EpubFixture.chapter("c$it", "Part $it", listOf(EpubFixture.longParagraph("$title $it")))
        }
        val built = EpubFixture.build(title = title, author = "Ann Author", chapters = chapters, drm = drm)
        val target = File(booksDir, fileName)
        built.copyTo(target, overwrite = true)
        return target
    }

    // --- new book ---------------------------------------------------------------

    @Test
    fun newBookIsParsedAndCached() {
        addBook("Sea Story - Ann Author.epub", "Sea Story")

        val books = store().refresh()

        assertEquals(1, parseCount)
        val book = books.single()
        assertEquals("sea-story", book.slug)
        assertEquals("Sea Story", book.title)
        assertEquals("Ann Author", book.author)
        assertEquals(listOf(1, 2), book.chapters.map { it.index })
        assertEquals(listOf("001.txt", "002.txt"), book.chapters.map { it.file })
        assertNull(book.problem)

        val folder = File(libraryDir, "sea-story")
        assertTrue(File(folder, "meta.json").isFile)
        val text = store().chapterText(book, book.chapters[0])!!
        assertTrue(text.startsWith("Sea Story 1"))
        assertEquals(text.length, book.chapters[0].chars)
    }

    @Test
    fun unchangedBookIsReadFromCacheNotParsedAgain() {
        addBook("a.epub", "Sea Story")
        val first = store().refresh()

        val second = store().refresh() // a fresh store, like the next app launch

        assertEquals(1, parseCount)
        assertEquals(first, second)
    }

    @Test
    fun countBooksToPrepareCountsOnlyNewBooks() {
        addBook("a.epub", "First")
        store().refresh()
        addBook("b.epub", "Second")
        addBook("c.epub", "Third")

        assertEquals(2, store().countBooksToPrepare())

        val remaining = mutableListOf<Int>()
        store().refresh { remaining.add(it) }
        assertEquals(listOf(2, 1), remaining)
        assertEquals(0, store().countBooksToPrepare())
    }

    // --- changed book ----------------------------------------------------------

    @Test
    fun changedBookIsParsedAgain() {
        val file = addBook("a.epub", "Sea Story", chapterCount = 2)
        store().refresh()

        addBook("a.epub", "Sea Story", chapterCount = 3)
        file.setLastModified(file.lastModified() + 60_000)
        val books = store().refresh()

        assertEquals(2, parseCount)
        assertEquals(3, books.single().chapters.size)
        assertTrue(File(libraryDir, "sea-story/003.txt").isFile)
    }

    @Test
    fun changedTitleMovesTheCacheFolder() {
        val file = addBook("a.epub", "Old Title")
        store().refresh()

        addBook("a.epub", "New Title")
        file.setLastModified(file.lastModified() + 60_000)
        val books = store().refresh()

        assertEquals("new-title", books.single().slug)
        assertFalse(File(libraryDir, "old-title").exists())
    }

    @Test
    fun parserVersionBumpReparsesEveryBook() {
        addBook("a.epub", "First")
        addBook("b.epub", "Second")
        store().refresh()

        store(parserVersion = PARSER_VERSION + 1).refresh()

        assertEquals(4, parseCount)
    }

    // --- deleted book ----------------------------------------------------------

    @Test
    fun deletedBookCacheFolderIsRemoved() {
        val gone = addBook("a.epub", "Gone Book")
        addBook("b.epub", "Kept Book")
        store().refresh()

        gone.delete()
        val books = store().refresh()

        assertEquals(listOf("kept-book"), books.map { it.slug })
        assertFalse(File(libraryDir, "gone-book").exists())
        assertTrue(File(libraryDir, "kept-book").exists())
    }

    @Test
    fun halfWrittenCacheFolderIsRemovedAndBookReparsed() {
        addBook("a.epub", "Sea Story")
        store().refresh()
        File(libraryDir, "sea-story/meta.json").delete() // as if the app died mid-parse

        val books = store().refresh()

        assertEquals(2, parseCount)
        assertEquals("sea-story", books.single().slug)
        assertTrue(File(libraryDir, "sea-story/meta.json").isFile)
    }

    // --- other rules -------------------------------------------------------------

    @Test
    fun onlyEpubFilesCountAndOldConvertedFoldersAreIgnored() {
        addBook("a.epub", "Sea Story")
        File(booksDir, "old-converted-book").mkdirs()
        File(booksDir, "old-converted-book/meta.json").writeText("{}")
        File(booksDir, "notes.txt").writeText("not a book")

        val books = store().refresh()

        assertEquals(listOf("sea-story"), books.map { it.slug })
    }

    @Test
    fun sameTitleGetsNumberedSlugsInFileNameOrder() {
        addBook("c.epub", "Twin")
        addBook("a.epub", "Twin")
        addBook("b.epub", "Twin")

        val books = store().refresh()

        assertEquals(listOf("a.epub", "b.epub", "c.epub"), books.map { it.source.fileName })
        assertEquals(listOf("twin", "twin-2", "twin-3"), books.map { it.slug })
    }

    @Test
    fun drmBookIsCachedAsUnreadableAndNotRetried() {
        addBook("Locked Book - Ann Author.epub", "Locked", drm = true)

        val book = store().refresh().single()
        store().refresh()

        assertEquals(BookProblem.DRM, book.problem)
        assertEquals("Locked Book - Ann Author", book.title)
        assertTrue(book.chapters.isEmpty())
        assertEquals(1, parseCount)
    }

    @Test
    fun brokenFileIsCachedAsUnreadable() {
        File(booksDir, "broken.epub").writeText("this is not a zip file")

        val book = store().refresh().single()

        assertEquals(BookProblem.UNREADABLE, book.problem)
    }

    @Test
    fun bookLooksUpBySlug() {
        addBook("a.epub", "Sea Story")
        store().refresh()

        assertEquals("Sea Story", store().book("sea-story")?.title)
        assertNull(store().book("no-such-book"))
    }

    @Test
    fun emptyOrMissingBooksFolderGivesEmptyLibrary() {
        booksDir.deleteRecursively()

        assertEquals(emptyList<BookMeta>(), store().refresh())
    }
}
