package com.jordan.watch.logic

/** The ways a library list can be sorted. */
enum class SortKey(val label: String) {
    Title("Title"),
    Year("Year"),
    RecentlyAdded("Recently added"),
    RecentlyWatched("Recently watched"),
    Runtime("Runtime"),
    Rating("Rating"),
    Genre("Genre"),
    Collection("Collection"),
    Resolution("Resolution"),
    FileSize("File size"),
    UnwatchedFirst("Unwatched first"),
}

/**
 * Sorts library lists. [ascending] = A–Z / low to high / oldest first; false flips it.
 * Titles missing the value go last either way. Ties fall back to title A–Z.
 */
object Sorting {

    fun sort(items: List<LibraryItem>, key: SortKey, ascending: Boolean = true): List<LibraryItem> {
        val byTitle = compareBy<LibraryItem> { sortTitle(it.title) }
        if (key == SortKey.Title) {
            return items.sortedWith(if (ascending) byTitle else byTitle.reversed())
        }
        val present = items.filter { valueOf(key, it) != null }
        val missing = items.filter { valueOf(key, it) == null }.sortedWith(byTitle)
        val main = Comparator<LibraryItem> { a, b -> compareValues(valueOf(key, a), valueOf(key, b)) }
        val ordered = if (ascending) main else main.reversed()
        return present.sortedWith(ordered.then(byTitle)) + missing
    }

    /** "The Matrix" -> "matrix"; "A Quiet Place" -> "quiet place". Lower case, no accents. */
    fun sortTitle(title: String): String {
        val folded = Search.normalize(title)
        for (article in ARTICLES) {
            if (folded.startsWith("$article ") && folded.length > article.length + 1) {
                return folded.substring(article.length + 1)
            }
        }
        return folded
    }

    private fun valueOf(key: SortKey, item: LibraryItem): Comparable<*>? = when (key) {
        SortKey.Title -> sortTitle(item.title)
        SortKey.Year -> item.year
        SortKey.RecentlyAdded -> item.addedAt
        SortKey.RecentlyWatched -> item.lastWatchedAt.takeIf { it > 0 }
        SortKey.Runtime -> item.runtimeMin
        SortKey.Rating -> item.rating
        SortKey.Genre -> item.genres.firstOrNull()?.lowercase()
        SortKey.Collection -> item.collection?.let {
            CollectionKey(it.lowercase(), item.collectionNumber ?: Int.MAX_VALUE)
        }
        SortKey.Resolution -> item.height
        SortKey.FileSize -> item.bytes
        SortKey.UnwatchedFirst -> item.watchState.ordinal
    }

    /** Collection name, then story order inside it. */
    private data class CollectionKey(val name: String, val number: Int) : Comparable<CollectionKey> {
        override fun compareTo(other: CollectionKey): Int =
            compareValuesBy(this, other, { it.name }, { it.number })
    }

    private val ARTICLES = listOf("the", "an", "a")
}
