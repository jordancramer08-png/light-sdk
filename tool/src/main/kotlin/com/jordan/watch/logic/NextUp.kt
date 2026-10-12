package com.jordan.watch.logic

/** What plays after (or before) the current film in a collection or episode in a show. */
object NextUp {

    const val COUNTDOWN_SECONDS = 10

    /** Collection films in story order: collectionNumber, then year, then title. */
    fun collectionOrder(movies: List<Movie>): List<Movie> = movies.sortedWith(
        compareBy<Movie>(
            { it.collectionNumber ?: Int.MAX_VALUE },
            { it.year ?: Int.MAX_VALUE },
            { Sorting.sortTitle(it.title) },
        ),
    )

    /** The next film in [current]'s collection, or null (no collection, or it's the last). */
    fun nextMovie(current: Movie, library: List<Movie>): Movie? = stepMovie(current, library, +1)

    fun previousMovie(current: Movie, library: List<Movie>): Movie? = stepMovie(current, library, -1)

    /** Episodes in order: season, then episode. Specials (season 0) come last. */
    fun episodeOrder(episodes: List<Episode>): List<Episode> = episodes.sortedWith(
        compareBy<Episode>({ if (it.season == 0) Int.MAX_VALUE else it.season }, { it.episode }),
    )

    /** The next episode of the same show, crossing into the next season. */
    fun nextEpisode(current: Episode, showEpisodes: List<Episode>): Episode? = stepEpisode(current, showEpisodes, +1)

    fun previousEpisode(current: Episode, showEpisodes: List<Episode>): Episode? = stepEpisode(current, showEpisodes, -1)

    private fun stepMovie(current: Movie, library: List<Movie>, delta: Int): Movie? {
        val name = current.collection ?: return null
        val ordered = collectionOrder(library.filter { it.collection.equals(name, ignoreCase = true) })
        val index = ordered.indexOfFirst { it.id == current.id }
        if (index < 0) return null
        return ordered.getOrNull(index + delta)
    }

    private fun stepEpisode(current: Episode, episodes: List<Episode>, delta: Int): Episode? {
        val ordered = episodeOrder(episodes)
        val index = ordered.indexOfFirst { it.id == current.id }
        if (index < 0) return null
        return ordered.getOrNull(index + delta)
    }
}
