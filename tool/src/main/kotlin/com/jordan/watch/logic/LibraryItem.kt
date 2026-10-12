package com.jordan.watch.logic

/** Where Jordan is with a title. */
enum class WatchState { Unwatched, InProgress, Watched }

/**
 * One row in the library (a movie or a show), with the facts sorting, filtering and search
 * need. Built from the json plus Jordan's .state files.
 */
data class LibraryItem(
    val id: String,
    val title: String,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val runtimeMin: Int? = null,
    val rating: Double? = null,
    val contentRating: String? = null,
    val collection: String? = null,
    val collectionNumber: Int? = null,
    val director: String? = null,
    val cast: List<String> = emptyList(),
    /** Video height in pixels (1080, 720, ...). */
    val height: Int? = null,
    val bytes: Long? = null,
    /** ISO date-time the PC script sent it ("2026-10-12T15:00:00"); sorts as text. */
    val addedAt: String? = null,
    /** Epoch ms it last played, or 0 if never. */
    val lastWatchedAt: Long = 0,
    val watchState: WatchState = WatchState.Unwatched,
) {
    companion object {
        fun fromMovie(movie: Movie, position: Position?, watchedAt: Long?): LibraryItem {
            val state = when {
                watchedAt != null -> WatchState.Watched
                position != null && position.positionMs > 0 -> WatchState.InProgress
                else -> WatchState.Unwatched
            }
            return LibraryItem(
                id = movie.id, title = movie.title, year = movie.year, genres = movie.genres,
                runtimeMin = movie.runtimeMin, rating = movie.rating,
                contentRating = movie.contentRating, collection = movie.collection,
                collectionNumber = movie.collectionNumber, director = movie.director,
                cast = movie.cast, height = movie.height, bytes = movie.bytes,
                addedAt = movie.sentAt, lastWatchedAt = position?.lastPlayedAt ?: 0,
                watchState = state,
            )
        }
    }
}
