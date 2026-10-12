package com.jordan.watch.logic

import kotlin.test.Test
import kotlin.test.assertEquals

class SortingTest {

    private val matrix = LibraryItem(
        id = "m", title = "The Matrix", year = 1999, genres = listOf("Sci-Fi"), runtimeMin = 136,
        rating = 8.7, height = 1080, bytes = 3_000_000_000, addedAt = "2026-10-01T10:00:00",
        lastWatchedAt = 300, watchState = WatchState.Watched,
    )
    private val avatar = LibraryItem(
        id = "a", title = "Avatar", year = 2009, genres = listOf("Action"), runtimeMin = 162,
        rating = 7.9, height = 720, bytes = 1_000_000_000, addedAt = "2026-10-05T10:00:00",
        lastWatchedAt = 100, watchState = WatchState.InProgress, collection = "Avatar", collectionNumber = 1,
    )
    private val quiet = LibraryItem(
        id = "q", title = "A Quiet Place", year = 2018, genres = listOf("Horror"), runtimeMin = 90,
        rating = null, height = 2160, bytes = 2_000_000_000, addedAt = "2026-10-03T10:00:00",
        lastWatchedAt = 0, watchState = WatchState.Unwatched,
    )
    private val way = LibraryItem(
        id = "w", title = "Avatar: The Way of Water", year = 2022, collection = "Avatar", collectionNumber = 2,
    )
    private val all = listOf(matrix, avatar, quiet, way)

    private fun ids(key: SortKey, ascending: Boolean = true, items: List<LibraryItem> = all) =
        Sorting.sort(items, key, ascending).map { it.id }

    @Test
    fun `title ignores leading The A An`() {
        assertEquals(listOf("a", "w", "m", "q"), ids(SortKey.Title))
        assertEquals(listOf("q", "m", "w", "a"), ids(SortKey.Title, ascending = false))
        assertEquals("american tail", Sorting.sortTitle("An American Tail"))
        assertEquals("matrix", Sorting.sortTitle("The Matrix"))
        assertEquals("a", Sorting.sortTitle("A"))
    }

    @Test
    fun year() {
        assertEquals(listOf("m", "a", "q", "w"), ids(SortKey.Year))
        assertEquals(listOf("w", "q", "a", "m"), ids(SortKey.Year, false))
    }

    @Test
    fun `recently added puts missing last`() {
        assertEquals(listOf("m", "q", "a", "w"), ids(SortKey.RecentlyAdded))
        assertEquals(listOf("a", "q", "m", "w"), ids(SortKey.RecentlyAdded, false))
    }

    @Test
    fun `recently watched skips never played`() {
        assertEquals(listOf("m", "a", "w", "q"), ids(SortKey.RecentlyWatched, false))
        assertEquals(listOf("a", "m", "w", "q"), ids(SortKey.RecentlyWatched))
    }

    @Test
    fun runtime() {
        assertEquals(listOf("q", "m", "a", "w"), ids(SortKey.Runtime))
        assertEquals(listOf("a", "m", "q", "w"), ids(SortKey.Runtime, false))
    }

    @Test
    fun `rating with nulls last`() {
        assertEquals(listOf("m", "a", "w", "q"), ids(SortKey.Rating, false))
        assertEquals(listOf("a", "m", "w", "q"), ids(SortKey.Rating))
    }

    @Test
    fun genre() {
        assertEquals(listOf("a", "q", "m", "w"), ids(SortKey.Genre))
        assertEquals(listOf("m", "q", "a", "w"), ids(SortKey.Genre, false))
    }

    @Test
    fun `collection keeps story order`() {
        assertEquals(listOf("a", "w", "m", "q"), ids(SortKey.Collection))
        assertEquals(listOf("w", "a", "m", "q"), ids(SortKey.Collection, false))
    }

    @Test
    fun resolution() {
        assertEquals(listOf("a", "m", "q", "w"), ids(SortKey.Resolution))
        assertEquals(listOf("q", "m", "a", "w"), ids(SortKey.Resolution, false))
    }

    @Test
    fun `file size`() {
        assertEquals(listOf("a", "q", "m", "w"), ids(SortKey.FileSize))
        assertEquals(listOf("m", "q", "a", "w"), ids(SortKey.FileSize, false))
    }

    @Test
    fun `unwatched first then in progress then watched`() {
        assertEquals(listOf("w", "q", "a", "m"), ids(SortKey.UnwatchedFirst))
        assertEquals(listOf("m", "a", "w", "q"), ids(SortKey.UnwatchedFirst, false))
    }
}
