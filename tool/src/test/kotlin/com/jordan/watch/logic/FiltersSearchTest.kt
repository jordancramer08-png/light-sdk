package com.jordan.watch.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FiltersSearchTest {

    private val amelie = LibraryItem(
        id = "1", title = "Amélie", year = 2001, genres = listOf("Comedy", "Romance"),
        contentRating = "R", height = 1080, director = "Jean-Pierre Jeunet", cast = listOf("Audrey Tautou"),
        watchState = WatchState.Watched,
    )
    private val oceans = LibraryItem(
        id = "2", title = "Ocean's Eleven", year = 2001, genres = listOf("Crime"),
        contentRating = "PG-13", height = 720, director = "Steven Soderbergh",
        cast = listOf("George Clooney", "Brad Pitt"), collection = "Ocean's", watchState = WatchState.InProgress,
    )
    private val hacksaw = LibraryItem(
        id = "3", title = "Hacksaw Ridge", year = 2016, genres = listOf("Drama", "War"),
        contentRating = "R", height = 2160, director = "Mel Gibson", cast = listOf("Andrew Garfield"),
    )
    private val all = listOf(amelie, oceans, hacksaw)

    private fun filtered(filter: LibraryFilter) = Filters.apply(all, filter).map { it.id }
    private fun searched(query: String) = Search.filter(all, query).map { it.id }

    @Test
    fun `genre filter ignores case`() = assertEquals(listOf("1"), filtered(LibraryFilter(genre = "romance")))

    @Test
    fun `decade filter`() {
        assertEquals(listOf("1", "2"), filtered(LibraryFilter(decade = 2000)))
        assertEquals(listOf(2000, 2010), Filters.decades(all))
        assertEquals(1990, Filters.decadeOf(1999))
    }

    @Test
    fun `watch state filters`() {
        assertEquals(listOf("1"), filtered(LibraryFilter(watchState = WatchState.Watched)))
        assertEquals(listOf("2"), filtered(LibraryFilter(watchState = WatchState.InProgress)))
        assertEquals(listOf("3"), filtered(LibraryFilter(watchState = WatchState.Unwatched)))
    }

    @Test
    fun `content rating collection and quality`() {
        assertEquals(listOf("1", "3"), filtered(LibraryFilter(contentRating = "r")))
        assertEquals(listOf("2"), filtered(LibraryFilter(collection = "Ocean's")))
        assertEquals(listOf("3"), filtered(LibraryFilter(quality = Quality.UHD)))
        assertEquals(listOf("2"), filtered(LibraryFilter(quality = Quality.HD720)))
        assertEquals(Quality.SD, Quality.of(480))
        assertEquals(null, Quality.of(null))
    }

    @Test
    fun `filters combine and empty filter keeps all`() {
        assertEquals(listOf("1"), filtered(LibraryFilter(decade = 2000, contentRating = "R")))
        assertEquals(listOf("1", "2", "3"), filtered(LibraryFilter()))
        assertTrue(LibraryFilter().isEmpty)
    }

    @Test
    fun `choice lists come from the library`() {
        assertEquals(listOf("Comedy", "Crime", "Drama", "Romance", "War"), Filters.genres(all))
        assertEquals(listOf("PG-13", "R"), Filters.contentRatings(all))
        assertEquals(listOf("Ocean's"), Filters.collections(all))
    }

    @Test
    fun `search ignores accents capitals and apostrophes`() {
        assertEquals(listOf("1"), searched("AMELIE"))
        assertEquals(listOf("2"), searched("oceans"))
        assertEquals(listOf("2"), searched("Ocean’s"))
    }

    @Test
    fun `every word must match in any order`() {
        assertEquals(listOf("2"), searched("eleven ocean"))
        assertEquals(emptyList(), searched("ocean ridge"))
    }

    @Test
    fun `search covers cast and director`() {
        assertEquals(listOf("2"), searched("clooney"))
        assertEquals(listOf("3"), searched("gibson garfield"))
        assertEquals(listOf("1"), searched("jean pierre"))
    }

    @Test
    fun `blank search keeps everything`() {
        assertEquals(listOf("1", "2", "3"), searched("   "))
        assertTrue(Search.matches(amelie, ""))
        assertFalse(Search.matches(amelie, "zzz"))
    }
}
