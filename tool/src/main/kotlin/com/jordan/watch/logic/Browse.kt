package com.jordan.watch.logic

import kotlinx.serialization.Serializable

/** The library tabs that can be sorted and filtered. Saved by [name] in settings.json. */
enum class BrowseTab { MOVIES, TV, COLLECTIONS }

/** One tab's sort, order and filters, remembered in settings.json. */
@Serializable
data class TabView(
    val sort: SortKey = SortKey.Title,
    val ascending: Boolean = true,
    val filter: LibraryFilter = LibraryFilter(),
)

/**
 * The Movies, TV and Collections tabs as lists of tiles: each title becomes a [LibraryItem]
 * (with Jordan's watched marks), is filtered, searched, sorted, then turned into a tile.
 * Pure, no Android; runs off the main thread.
 */
object Browse {

    /** The tab's items, before filtering. Shows and collections add up their parts. */
    fun items(tab: BrowseTab, library: Library, positions: Map<String, Position>, watched: Map<String, Long>): List<LibraryItem> =
        when (tab) {
            BrowseTab.MOVIES -> library.movies.map { LibraryItem.fromMovie(it.movie, positions[it.movie.id], watched[it.movie.id]) }
            BrowseTab.TV -> library.shows.map { showItem(it, positions, watched) }
            BrowseTab.COLLECTIONS -> library.collections.map { collectionItem(it, positions, watched) }
        }

    /** Filters, searches ([query] may be blank) and sorts, then makes tiles. */
    fun tiles(
        tab: BrowseTab,
        library: Library,
        positions: Map<String, Position>,
        watched: Map<String, Long>,
        view: TabView,
        query: String = "",
    ): List<Tile> {
        val chosen = Sorting.sort(Search.filter(Filters.apply(items(tab, library, positions, watched), view.filter), query), view.sort, view.ascending)
        val others = when (tab) {
            BrowseTab.MOVIES -> emptyMap()
            BrowseTab.TV -> Shelves.shows(library).associateBy { it.id }
            BrowseTab.COLLECTIONS -> Shelves.collections(library).associateBy { it.id }
        }
        return chosen.mapNotNull { item ->
            if (tab == BrowseTab.MOVIES) library.movie(item.id)?.let { movieTile(it, positions, watched) } else others[item.id]
        }
    }

    /** A movie tile with its watched tick and, when started, its progress bar. */
    fun movieTile(entry: MovieEntry, positions: Map<String, Position>, watched: Map<String, Long>): Tile {
        val id = entry.movie.id
        val position = positions[id]?.positionMs ?: 0
        val progress = if (position > 0 && id !in watched) Resume.progress(position, (entry.movie.runtimeMin ?: 0) * 60_000L) else null
        return Shelves.movieTile(entry, id in watched).copy(progress = progress)
    }

    /** The library without titles waiting to be removed by the PC (see [RemovalRepository]). */
    fun visible(library: Library, hidden: Collection<String>): Library {
        if (hidden.isEmpty()) return library
        val gone = hidden.toSet()
        return Library(library.index.copy(movies = library.movies.filter { it.movie.id !in gone }))
    }

    /** Collection id as used by tiles: "collection:Harry Potter". */
    fun collectionId(name: String): String = "collection:$name"

    fun collectionName(id: String): String = id.removePrefix("collection:")

    private fun showItem(entry: ShowEntry, positions: Map<String, Position>, watched: Map<String, Long>): LibraryItem {
        val s = entry.show
        val ids = entry.episodes.map { it.episode.id }
        return LibraryItem(
            id = s.id, title = s.title, year = s.year, genres = s.genres, rating = s.rating,
            contentRating = s.contentRating, cast = s.cast,
            runtimeMin = entry.episodes.sumOf { it.episode.runtimeMin ?: 0 }.takeIf { it > 0 },
            lastWatchedAt = ids.maxOfOrNull { positions[it]?.lastPlayedAt ?: 0 } ?: 0,
            watchState = combinedState(ids, positions, watched),
        )
    }

    private fun collectionItem(c: MovieCollection, positions: Map<String, Position>, watched: Map<String, Long>): LibraryItem {
        val films = c.movies.map { it.movie }
        val ids = films.map { it.id }
        return LibraryItem(
            id = collectionId(c.name), title = c.name,
            year = films.mapNotNull { it.year }.minOrNull(),
            genres = films.flatMap { it.genres }.distinct(),
            runtimeMin = films.sumOf { it.runtimeMin ?: 0 }.takeIf { it > 0 },
            rating = films.mapNotNull { it.rating }.takeIf { it.isNotEmpty() }?.average(),
            contentRating = films.firstNotNullOfOrNull { it.contentRating },
            collection = c.name,
            director = films.mapNotNull { it.director }.distinct().joinToString(" ").ifEmpty { null },
            cast = films.flatMap { it.cast }.distinct(),
            height = films.mapNotNull { it.height }.maxOrNull(),
            bytes = films.sumOf { it.bytes ?: 0L }.takeIf { it > 0 },
            addedAt = films.mapNotNull { it.sentAt }.maxOrNull(),
            lastWatchedAt = ids.maxOfOrNull { positions[it]?.lastPlayedAt ?: 0 } ?: 0,
            watchState = combinedState(ids, positions, watched),
        )
    }

    /** All watched = Watched; any watched or started = In progress; else Unwatched. */
    fun combinedState(ids: List<String>, positions: Map<String, Position>, watched: Map<String, Long>): WatchState = when {
        ids.isNotEmpty() && ids.all { it in watched } -> WatchState.Watched
        ids.any { it in watched || (positions[it]?.positionMs ?: 0) > 0 } -> WatchState.InProgress
        else -> WatchState.Unwatched
    }
}

/** What each sort's two directions are called on the Sort & Filter screen. */
object SortLabels {
    fun order(key: SortKey, ascending: Boolean): String {
        val (up, down) = when (key) {
            SortKey.Title, SortKey.Genre, SortKey.Collection -> "A–Z" to "Z–A"
            SortKey.Year, SortKey.RecentlyAdded, SortKey.RecentlyWatched -> "Oldest first" to "Newest first"
            SortKey.Runtime -> "Shortest first" to "Longest first"
            SortKey.Rating, SortKey.Resolution -> "Lowest first" to "Highest first"
            SortKey.FileSize -> "Smallest first" to "Largest first"
            SortKey.UnwatchedFirst -> "Unwatched first" to "Watched first"
        }
        return if (ascending) up else down
    }

    /** "Year · Newest first · 2 filters". */
    fun summary(view: TabView): String {
        val filters = filterCount(view.filter)
        val parts = listOf(view.sort.label, order(view.sort, view.ascending)) +
            listOfNotNull(filters.takeIf { it > 0 }?.let { if (it == 1) "1 filter" else "$it filters" })
        return parts.distinct().joinToString(" · ")
    }

    fun filterCount(f: LibraryFilter): Int =
        listOf(f.genre, f.decade, f.watchState, f.contentRating, f.collection, f.quality).count { it != null }

    /** 1990 -> "1990s". */
    fun decade(year: Int): String = "${year}s"

    fun watchState(state: WatchState): String = when (state) {
        WatchState.Unwatched -> "Unwatched"
        WatchState.InProgress -> "In progress"
        WatchState.Watched -> "Watched"
    }
}
