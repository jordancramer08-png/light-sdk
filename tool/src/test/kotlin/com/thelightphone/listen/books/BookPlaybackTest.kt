package com.thelightphone.listen.books

import com.thelightphone.listen.playback.MusicState
import com.thelightphone.listen.playback.MusicStateStore
import com.thelightphone.listen.playback.speedText
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookPlaybackTest {

    private fun file(name: String, ms: Long) = BookFile(path = name, label = name, size = 1, modified = 1, durationMs = ms)

    private val book = Book(
        id = "A/B",
        folder = "A/B",
        title = "B",
        author = "A",
        files = listOf(file("1.mp3", 60_000), file("2.mp3", 120_000), file("3.mp3", 60_000)),
    )

    @Test
    fun `rewind is nothing for a moment, short under ten minutes, long after`() {
        assertEquals(0, rewindMs(500, 3, 10))
        assertEquals(3_000, rewindMs(30_000, 3, 10))
        assertEquals(3_000, rewindMs(9 * 60_000L, 3, 10))
        assertEquals(10_000, rewindMs(10 * 60_000L, 3, 10))
        assertEquals(10_000, rewindMs(3 * 86_400_000L, 3, 10))
        assertEquals(0, rewindMs(60_000, -1, 10)) // a bad setting never goes forward
    }

    @Test
    fun `a place in the whole book is found across files`() {
        assertEquals(BookPosition(0, 0), locate(book, -5_000))
        assertEquals(BookPosition(0, 59_000), locate(book, 59_000))
        assertEquals(BookPosition(1, 0), locate(book, 60_000))
        assertEquals(BookPosition(1, 90_000), locate(book, 150_000))
        assertEquals(BookPosition(2, 60_000), locate(book, 999_000)) // past the end: the end
    }

    @Test
    fun `back and forward round trip through the book position`() {
        val here = BookPosition(fileIndex = 1, positionMs = 10_000)
        assertEquals(BookPosition(0, 55_000), locate(book, bookPositionMs(book, here) - 15_000))
        assertEquals(BookPosition(1, 40_000), locate(book, bookPositionMs(book, here) + 30_000))
    }

    @Test
    fun `the last 30 seconds count as finished`() {
        assertTrue(isNearEnd(book, BookPosition(fileIndex = 2, positionMs = 31_000)))
        assertFalse(isNearEnd(book, BookPosition(fileIndex = 2, positionMs = 29_000)))
        // The flag alone isn't "near the end".
        assertFalse(isNearEnd(book, BookPosition(fileIndex = 0, positionMs = 0, finished = true)))
    }

    @Test
    fun `speeds read naturally`() {
        assertEquals("0.75×", speedText(0.75f))
        assertEquals("1.0×", speedText(1f))
        assertEquals("1.1×", speedText(1.1f))
        assertEquals("1.25×", speedText(1.25f))
        assertEquals("2.0×", speedText(2f))
    }

    @Test
    fun `music state remembers a loaded book and older files still load`() {
        val dir = Files.createTempDirectory("listen").toFile()
        val store = MusicStateStore(java.io.File(dir, "music_state.json"))
        store.save(MusicState(paths = listOf("a.mp3"), book = "A/B"))
        assertEquals("A/B", store.load()?.book)

        java.io.File(dir, "music_state.json").writeText("""{"version":1,"paths":["a.mp3"],"index":0}""")
        assertNull(store.load()?.book)
        dir.deleteRecursively()
    }

    @Test
    fun `book positions keep speed and finished`() {
        val dir = Files.createTempDirectory("listen").toFile()
        val positions = BookPositionsFile(java.io.File(dir, "book_positions.json"))
        val saved = BookPosition(fileIndex = 2, positionMs = 1234, speed = 1.5f, lastPlayedAt = 99, finished = true)
        positions.save(BookPositionsData(books = mapOf("A/B" to saved)))
        assertEquals(saved, positions.load().books["A/B"])
        dir.deleteRecursively()
    }
}
