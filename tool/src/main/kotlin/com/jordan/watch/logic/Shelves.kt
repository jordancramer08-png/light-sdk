package com.jordan.watch.logic

/**
 * One poster in a grid or list: a movie, a show, an episode or a collection. [poster] is
 * relative to /sdcard/Watch ("Movies/_Singles/Up (2009)/poster.jpg"), or null for none.
 * [progress] is 0..1 for a started title (the bar under the poster), else null.
 */
data class Tile(
    val id: String,
    val title: String,
    val detail: String,
    val poster: String?,
    val progress: Float? = null,
    val watched: Boolean = false,
)

/** The library turned into tiles: Home's two shelves and the three tabs. Pure, no Android. */
object Shelves {
    const val RECENT_COUNT = 20

    /** Started-but-unfinished movies and episodes, most recently played first. */
    fun continueWatching(library: Library, positions: Map<String, Position>, watched: Map<String, Long>): List<Tile> =
        positions.filter { (id, p) -> p.positionMs > 0 && id !in watched }
            .entries.sortedByDescending { it.value.lastPlayedAt }
            .mapNotNull { (id, p) -> inProgressTile(library, id, p.positionMs) }

    private fun inProgressTile(library: Library, id: String, positionMs: Long): Tile? {
        library.movie(id)?.let { entry ->
            val progress = Resume.progress(positionMs, minutesToMs(entry.movie.runtimeMin))
            return movieTile(entry).copy(progress = progress, detail = timeLeft(positionMs, entry.movie.runtimeMin))
        }
        val (show, episode) = library.episode(id) ?: return null
        val e = episode.episode
        return Tile(
            id = id,
            title = show.show.title,
            detail = "S${e.season} E${e.episode} · " + timeLeft(positionMs, e.runtimeMin),
            poster = posterPath(show.folder, show.show.poster),
            progress = Resume.progress(positionMs, minutesToMs(e.runtimeMin)),
        )
    }

    /** Newest sends first (sentAt), then by title; at most [count]. */
    fun recentlyAdded(library: Library, watched: Map<String, Long>, count: Int = RECENT_COUNT): List<Tile> =
        library.movies.sortedWith(
            compareByDescending<MovieEntry> { it.movie.sentAt ?: "" }.thenBy { Sorting.sortTitle(it.movie.title) },
        ).take(count).map { movieTile(it, it.movie.id in watched) }

    /** All movies A–Z (ignoring The/A/An). Sort and filter screens arrive in Session 5. */
    fun movies(library: Library, watched: Map<String, Long>): List<Tile> =
        library.movies.sortedBy { Sorting.sortTitle(it.movie.title) }.map { movieTile(it, it.movie.id in watched) }

    /** All shows A–Z, with their episode count. */
    fun shows(library: Library): List<Tile> =
        library.shows.sortedBy { Sorting.sortTitle(it.show.title) }.map { entry ->
            Tile(
                id = entry.show.id,
                title = entry.show.title,
                detail = count(library.seasons(entry).size, "season") + " · " + count(entry.episodes.size, "episode"),
                poster = posterPath(entry.folder, entry.show.poster),
            )
        }

    /** Collections A–Z, each shown with its first film's poster. */
    fun collections(library: Library): List<Tile> =
        library.collections.map { collection ->
            val first = collection.movies.first()
            Tile(
                id = "collection:" + collection.name,
                title = collection.name,
                detail = count(collection.movies.size, "film"),
                poster = posterPath(first.folder, first.movie.poster),
            )
        }

    fun movieTile(entry: MovieEntry, watched: Boolean = false): Tile {
        val m = entry.movie
        return Tile(
            id = m.id,
            title = m.title,
            detail = listOfNotNull(m.year?.toString(), m.runtimeMin?.let(::runtime)).joinToString(" · "),
            poster = posterPath(entry.folder, m.poster),
            watched = watched,
        )
    }

    /** Splits tiles into grid rows of [columns]; every row is one same-height lazy item. */
    fun gridRows(tiles: List<Tile>, columns: Int): List<List<Tile>> = tiles.chunked(columns.coerceIn(2, 3))

    /** "1h 52m", "45m". */
    fun runtime(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

    /** "1h 10m left", or "Started" when the runtime isn't known. */
    fun timeLeft(positionMs: Long, runtimeMin: Int?): String {
        val total = runtimeMin ?: return "Started"
        val left = (total - (positionMs / 60_000).toInt()).coerceAtLeast(1)
        return runtime(left) + " left"
    }

    /**
     * A steady hue (0–360) for a title's placeholder, so a missing poster is still told apart
     * by color as well as by its letter.
     */
    fun placeholderHue(title: String): Float = (Sorting.sortTitle(title).hashCode().mod(360)).toFloat()

    /** The letter on a placeholder: the title's first letter or digit (after The/A/An), or "?". */
    fun placeholderLetter(title: String): String =
        Sorting.sortTitle(title).firstOrNull { it.isLetterOrDigit() }?.uppercase()
            ?: title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

    private fun posterPath(folder: String, poster: String?): String? = poster?.let { "$folder/$it" }

    private fun minutesToMs(minutes: Int?): Long = (minutes ?: 0) * 60_000L

    private fun count(n: Int, word: String): String = if (n == 1) "1 $word" else "$n ${word}s"
}
