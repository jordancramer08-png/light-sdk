package com.thelightphone.listen.books

import com.thelightphone.listen.music.RawTags
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BooksTest {

    // ---- book.json ----

    @Test
    fun `book json is read field by field`() {
        val json = parseBookJson(
            """
            {"schema":1,"id":"Frank Herbert/Dune Chronicles/Dune Messiah","title":"Dune Messiah",
             "author":"Frank Herbert","series":"Dune Chronicles","seriesNumber":2,"narrator":"",
             "year":1969,"category":"","cover":"cover.jpg",
             "files":[{"name":"01.mp3","label":"Chapter 1","bytes":123,
                       "chapters":[{"title":"B","startMs":5000},{"title":"A","startMs":0}]}],
             "sentAt":"2026-09-29T16:36:18"}
            """,
        )!!
        assertEquals("Frank Herbert/Dune Chronicles/Dune Messiah", json.id)
        assertEquals("Dune Messiah", json.title)
        assertEquals(2.0, json.seriesNumber)
        assertNull(json.narrator) // blank reads as missing
        assertEquals("1969", json.year)
        assertEquals("cover.jpg", json.cover)
        assertEquals(listOf("A", "B"), json.files.single().chapters.map { it.title }) // in start order
    }

    @Test
    fun `odd book json values never lose the rest`() {
        val json = parseBookJson(
            "﻿{\"title\":\"T\",\"seriesNumber\":\"2.5\",\"cover\":null,\"files\":[{\"name\":\"..\\\\x.mp3\"},{\"name\":\"CD1\\\\01.mp3\"},{\"label\":\"no name\"}]}",
        )!!
        assertEquals("T", json.title)
        assertEquals(2.5, json.seriesNumber)
        assertNull(json.cover)
        assertEquals(listOf("CD1/01.mp3"), json.files.map { it.name }) // ".." refused, nameless dropped
        assertNull(parseBookJson("not json"))
        assertNull(parseBookJson("[1,2]"))
    }

    // ---- Scanning and fallbacks ----

    private fun tempBooks(): File = Files.createTempDirectory("books").toFile()

    private fun File.put(path: String, text: String = "x"): File =
        File(this, path).also { it.parentFile.mkdirs(); it.writeText(text) }

    private fun scanner(dir: File, reads: MutableList<String> = mutableListOf()) =
        BookScanner(dir, File(dir, "none.txt")) { file ->
            reads += file.name
            RawTags(title = "Same", artist = "Tag Artist", durationMs = "60000")
        }

    @Test
    fun `a book json book uses its files in order and skips missing ones`() {
        val dir = tempBooks()
        dir.put("Frank Herbert/Dune/Dune Messiah/b.mp3")
        dir.put("Frank Herbert/Dune/Dune Messiah/a.mp3")
        dir.put(
            "Frank Herbert/Dune/Dune Messiah/book.json",
            """{"id":"dm","title":"Dune Messiah","author":"Frank Herbert","files":[
               {"name":"b.mp3","label":"One"},{"name":"gone.mp3","label":"Gone"},{"name":"a.mp3","label":"Two"}]}""",
        )
        val book = scanner(dir).scan(emptyList()).single()
        assertEquals("dm", book.id)
        assertEquals(listOf("b.mp3", "a.mp3"), book.files.map { it.path })
        assertEquals(listOf("One", "Two"), book.files.map { it.label })
        assertEquals(120_000, book.durationMs)
    }

    @Test
    fun `without book json the folders and tags fill in`() {
        val dir = tempBooks()
        dir.put("Brandon Sanderson/Mistborn/02 - The Well of Ascension/10.mp3")
        dir.put("Brandon Sanderson/Mistborn/02 - The Well of Ascension/2.mp3")
        dir.put("Brandon Sanderson/Mistborn/02 - The Well of Ascension/book.json", "{ broken")
        val book = scanner(dir).scan(emptyList()).single()
        assertEquals("Brandon Sanderson/Mistborn/02 - The Well of Ascension", book.id)
        assertEquals("The Well of Ascension", book.title)
        assertEquals("Brandon Sanderson", book.author)
        assertEquals("Mistborn", book.series)
        assertEquals(2.0, book.seriesNumber)
        assertEquals(listOf("2.mp3", "10.mp3"), book.files.map { it.path }) // natural order
        assertEquals(listOf("2", "10"), book.files.map { it.label }) // same tag everywhere: file names
        assertFalse(book.hasBookJson)
    }

    @Test
    fun `a rescan reads only new or changed files`() {
        val dir = tempBooks()
        dir.put("A/Book/1.mp3")
        dir.put("A/Book/2.mp3")
        val first = scanner(dir).scan(emptyList())
        dir.put("A/Book/3.mp3")
        val reads = mutableListOf<String>()
        val again = scanner(dir, reads).scan(first)
        assertEquals(listOf("3.mp3"), reads)
        assertEquals(3, again.single().files.size)
    }

    // ---- Progress and chapters ----

    private fun book(
        id: String,
        title: String = id,
        author: String = "Author",
        series: String? = null,
        number: Double? = null,
        files: List<BookFile> = listOf(BookFile("1.mp3", "One", 1, 1, 600_000), BookFile("2.mp3", "Two", 1, 1, 400_000)),
    ) = Book(id = id, folder = id, title = title, author = author, series = series, seriesNumber = number, files = files)

    @Test
    fun `progress counts earlier files and the last 30 seconds is finished`() {
        val b = book("b")
        assertEquals(null, progressText(b, null))
        assertEquals("65%", progressText(b, BookPosition(fileIndex = 1, positionMs = 50_000)))
        assertEquals("Finished", progressText(b, BookPosition(fileIndex = 1, positionMs = 375_000)))
        assertEquals("Finished", progressText(b, BookPosition(finished = true)))
        assertFalse(isInProgress(b, BookPosition()))
    }

    @Test
    fun `m4b chapter marks become chapters with time left`() {
        val m4b = book(
            "m",
            files = listOf(
                BookFile("b.m4b", "Book", 1, 1, 3_600_000, chapters = listOf(ChapterMark("Opening", 0), ChapterMark("", 1_200_000))),
            ),
        )
        val chapters = chaptersOf(m4b)
        assertEquals(listOf("Opening", "Chapter 2"), chapters.map { it.title })
        assertEquals(
            "Chapter 2 of 2, 30:00 left in chapter",
            chapterSpotText(chapters, BookPosition(positionMs = 1_800_000)),
        )
    }

    // ---- Grouping and sorting ----

    @Test
    fun `series group into one row in number order and single ones stay books`() {
        val books = listOf(
            book("m2", "The Well of Ascension", "Brandon Sanderson", "Mistborn", 2.0),
            book("m1", "The Final Empire", "Brandon Sanderson", "Mistborn", 1.0),
            book("m25", "The Alloy of Law", "Brandon Sanderson", "Mistborn", 2.5),
            book("w", "Warbreaker", "Brandon Sanderson"),
            book("hp", "Philosopher's Stone", "J.K. Rowling", "Harry Potter", 1.0),
            book("mar", "The Martian", "Andy Weir"),
        )
        val entries = libraryEntries(books, emptyMap(), BookSortField.AUTHOR, descending = false)
        assertEquals(listOf("The Martian", "Mistborn", "Warbreaker", "Philosopher's Stone"), entries.map { it.title })
        val series = entries[1] as BookEntry.Series
        assertEquals(listOf("m1", "m2", "m25"), series.books.map { it.id })

        val zToA = libraryEntries(books, emptyMap(), BookSortField.AUTHOR, descending = true)
        assertEquals(listOf("Philosopher's Stone", "Mistborn", "Warbreaker", "The Martian"), zToA.map { it.title })

        val byTitle = libraryEntries(books, emptyMap(), BookSortField.TITLE, descending = false)
        assertEquals(listOf("The Martian", "Mistborn", "Philosopher's Stone", "Warbreaker"), byTitle.map { it.title })
    }

    @Test
    fun `recently played puts never played last and continue listening is newest first`() {
        val books = listOf(book("a"), book("b"), book("c"), book("d"))
        val positions = mapOf(
            "a" to BookPosition(positionMs = 1_000, lastPlayedAt = 100),
            "b" to BookPosition(positionMs = 1_000, lastPlayedAt = 300),
            "c" to BookPosition(finished = true, lastPlayedAt = 200),
        )
        assertEquals(listOf("b", "c", "a", "d"), libraryEntries(books, positions, BookSortField.RECENT, false).map { it.title })
        assertEquals(listOf("a", "c", "b", "d"), libraryEntries(books, positions, BookSortField.RECENT, true).map { it.title })
        assertEquals(listOf("b", "a"), continueListening(books, positions).map { it.id })
        assertTrue(isFinished(books[2], positions["c"]))
    }
}
