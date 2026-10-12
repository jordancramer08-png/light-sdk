package com.jordan.watch.logic

import kotlin.random.Random

/** "Surprise me": a random unwatched movie, optionally of one genre and no longer than a limit. */
object Surprise {

    /** The runtime limits offered, in minutes (null = any length). */
    val RUNTIME_LIMITS: List<Int?> = listOf(null, 90, 105, 120, 150)

    /** The movies it may pick from: not watched, not started, matching [genre] and [maxRuntimeMin]. */
    fun candidates(items: List<LibraryItem>, genre: String?, maxRuntimeMin: Int?): List<LibraryItem> =
        items.filter { item ->
            item.watchState == WatchState.Unwatched &&
                (genre == null || item.genres.any { it.equals(genre, ignoreCase = true) }) &&
                (maxRuntimeMin == null || (item.runtimeMin != null && item.runtimeMin <= maxRuntimeMin))
        }

    /** One random pick, never [avoidId] (the last pick) unless it's the only one. Null = none fit. */
    fun pick(
        items: List<LibraryItem>,
        genre: String?,
        maxRuntimeMin: Int?,
        avoidId: String? = null,
        random: Random = Random.Default,
    ): LibraryItem? {
        val all = candidates(items, genre, maxRuntimeMin)
        val fresh = all.filter { it.id != avoidId }.ifEmpty { all }
        return if (fresh.isEmpty()) null else fresh[random.nextInt(fresh.size)]
    }

    /** "Any length", "Up to 1h 30m". */
    fun limitLabel(maxRuntimeMin: Int?): String =
        if (maxRuntimeMin == null) "Any length" else "Up to " + Shelves.runtime(maxRuntimeMin)
}
