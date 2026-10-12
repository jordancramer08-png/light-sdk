package com.jordan.watch.logic

import com.jordan.watch.dev.DevLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShelvesTest {

    private fun movie(title: String, year: Int, sentAt: String? = null, runtime: Int? = 100, collection: String? = null, n: Int? = null) =
        MovieEntry(
            folder = "Movies/${collection ?: "_Singles"}/$title ($year)", video = "movie.mp4", fingerprint = "x",
            movie = Movie(id = "${collection ?: "_Singles"}/$title ($year)", title = title, year = year, sentAt = sentAt,
                runtimeMin = runtime, collection = collection, collectionNumber = n),
        )

    private val library = Library(
        LibraryIndex(
            movies = listOf(
                movie("The Matrix", 1999, "2026-01-01T00:00:00"),
                movie("Up", 2009, "2026-03-01T00:00:00"),
                movie("Alien", 1979, "2026-02-01T00:00:00", runtime = null),
                movie("Part Two", 2002, collection = "Saga", n = 2),
                movie("Part One", 2001, collection = "Saga", n = 1),
            ),
        ),
    )

    @Test
    fun `continue watching is newest first, skips watched and unknown ids`() {
        val positions = mapOf(
            "_Singles/Up (2009)" to Position(50 * 60_000L, lastPlayedAt = 1),
            "_Singles/The Matrix (1999)" to Position(10 * 60_000L, lastPlayedAt = 5),
            "_Singles/Alien (1979)" to Position(10_000, lastPlayedAt = 9),
            "gone/Old (1990)" to Position(10_000, lastPlayedAt = 10),
        )
        val tiles = Shelves.continueWatching(library, positions, watched = mapOf("_Singles/Alien (1979)" to 1L))
        assertEquals(listOf("The Matrix", "Up"), tiles.map { it.title })
        assertEquals(0.5f, tiles[1].progress)
        assertEquals("50m left", tiles[1].detail)
    }

    @Test
    fun `recently added is newest send first`() {
        assertEquals(listOf("Up", "Alien", "The Matrix"), Shelves.recentlyAdded(library, emptyMap(), 3).map { it.title })
    }

    @Test
    fun `movies sort ignoring The and mark watched`() {
        val tiles = Shelves.movies(library, mapOf("_Singles/Up (2009)" to 1L))
        assertEquals(listOf("Alien", "The Matrix", "Part One", "Part Two", "Up"), tiles.map { it.title })
        assertTrue(tiles.last().watched)
        assertEquals("Movies/_Singles/Up (2009)/poster.jpg", tiles.last().poster)
    }

    @Test
    fun `collections use the first film's poster`() {
        val saga = Shelves.collections(library).single()
        assertEquals("2 films", saga.detail)
        assertEquals("Movies/Saga/Part One (2001)/poster.jpg", saga.poster)
    }

    @Test
    fun `grid rows hold 2 or 3 tiles`() {
        val tiles = Shelves.movies(library, emptyMap())
        assertEquals(listOf(3, 2), Shelves.gridRows(tiles, 3).map { it.size })
        assertEquals(listOf(2, 2, 1), Shelves.gridRows(tiles, 2).map { it.size })
    }

    @Test
    fun `runtime, time left and placeholder letter`() {
        assertEquals("1h 52m", Shelves.runtime(112))
        assertEquals("45m", Shelves.runtime(45))
        assertEquals("Started", Shelves.timeLeft(1000, null))
        assertEquals("M", Shelves.placeholderLetter("The Matrix"))
        assertEquals("E", Shelves.placeholderLetter("Élan"))
        assertEquals(Shelves.placeholderHue("Up"), Shelves.placeholderHue("Up"))
    }

    @Test
    fun `fake library has 300 movies with unique ids and a continue watching shelf`() {
        val index = assertNotNull(DevLibrary.fakeIndex())
        assertEquals(DevLibrary.MOVIE_COUNT, index.movies.map { it.movie.id }.toSet().size)
        assertEquals(DevLibrary.SHOW_COUNT, index.shows.size)
        val fake = Library(index)
        assertEquals(7, Shelves.continueWatching(fake, DevLibrary.fakePositions(), emptyMap()).size)
        assertTrue(Shelves.collections(fake).isNotEmpty())
    }
}
