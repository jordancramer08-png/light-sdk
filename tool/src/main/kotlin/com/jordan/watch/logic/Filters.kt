package com.jordan.watch.logic

import kotlinx.serialization.Serializable

/** Picture quality bands, from the video height. */
enum class Quality(val label: String) {
    SD("SD"), HD720("720p"), HD1080("1080p"), UHD("4K");

    companion object {
        /** Unknown height -> null (never matches a quality filter). */
        fun of(height: Int?): Quality? = when {
            height == null || height <= 0 -> null
            height >= 2000 -> UHD
            height >= 1000 -> HD1080
            height >= 700 -> HD720
            else -> SD
        }
    }
}

/** Which filters are on. A null field means "any". */
@Serializable
data class LibraryFilter(
    val genre: String? = null,
    /** First year of the decade, e.g. 1990 for the 90s. */
    val decade: Int? = null,
    val watchState: WatchState? = null,
    val contentRating: String? = null,
    val collection: String? = null,
    val quality: Quality? = null,
) {
    val isEmpty: Boolean get() = this == LibraryFilter()
}

object Filters {

    fun apply(items: List<LibraryItem>, filter: LibraryFilter): List<LibraryItem> =
        items.filter { matches(it, filter) }

    fun matches(item: LibraryItem, filter: LibraryFilter): Boolean {
        filter.genre?.let { g -> if (item.genres.none { it.equals(g, ignoreCase = true) }) return false }
        filter.decade?.let { d -> if (item.year == null || decadeOf(item.year) != d) return false }
        filter.watchState?.let { if (item.watchState != it) return false }
        filter.contentRating?.let { if (!it.equals(item.contentRating, ignoreCase = true)) return false }
        filter.collection?.let { if (!it.equals(item.collection, ignoreCase = true)) return false }
        filter.quality?.let { if (Quality.of(item.height) != it) return false }
        return true
    }

    /** 1994 -> 1990. */
    fun decadeOf(year: Int): Int = year - year % 10

    /** The choices to offer on the filter screen, from what's actually in the library. */
    fun genres(items: List<LibraryItem>): List<String> =
        items.flatMap { it.genres }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    fun decades(items: List<LibraryItem>): List<Int> =
        items.mapNotNull { it.year?.let(::decadeOf) }.distinct().sorted()

    fun contentRatings(items: List<LibraryItem>): List<String> =
        items.mapNotNull { it.contentRating }.distinct().sorted()

    fun collections(items: List<LibraryItem>): List<String> =
        items.mapNotNull { it.collection }.distinct().sortedBy { it.lowercase() }
}
