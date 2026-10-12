package com.jordan.watch.dev

import com.jordan.watch.logic.Episode
import com.jordan.watch.logic.EpisodeEntry
import com.jordan.watch.logic.LibraryIndex
import com.jordan.watch.logic.Movie
import com.jordan.watch.logic.MovieEntry
import com.jordan.watch.logic.Position
import com.jordan.watch.logic.Show
import com.jordan.watch.logic.ShowEntry

/**
 * DEBUG BUILDS ONLY (src/debug; the release build has an empty twin). A made-up library of
 * [MOVIE_COUNT] movies and [SHOW_COUNT] shows, held in memory only: nothing is written to
 * the phone, to .state, or to git. Turned on by FeatureFlags.FAKE_LIBRARY.
 * There are no poster files, so every tile shows its colored letter placeholder.
 */
object DevLibrary {
    const val MOVIE_COUNT = 300
    const val SHOW_COUNT = 25

    private val words = listOf(
        "Silent", "River", "The Last", "Midnight", "Iron", "Golden", "A Quiet", "Broken", "Summer",
        "Distant", "Paper", "Electric", "Hidden", "Northern", "Winter", "Crimson", "Lost", "Ember",
    )
    private val nouns = listOf(
        "Harbor", "Kingdom", "Signal", "Garden", "Frontier", "Promise", "Machine", "Letters",
        "Mountain", "Voyage", "Witness", "Orchard", "Lantern", "Echo", "Horizon", "Station",
    )
    private val genres = listOf("Drama", "Comedy", "Action", "Sci-Fi", "Family", "War", "Animation", "Thriller")
    private val ratings = listOf("G", "PG", "PG-13", "R")
    private val collections = listOf("Harbor Saga", "Iron Trilogy", "Ember Chronicles", "Station Files")

    /** The fake library. The same every time (no randomness), so screens look the same. */
    fun fakeIndex(): LibraryIndex? = LibraryIndex(stamp = "fake", movies = movies(), shows = shows())

    /** A few titles started, so Continue Watching has something to show. */
    fun fakePositions(): Map<String, Position> {
        val now = System.currentTimeMillis()
        val movieIds = movies().take(6).mapIndexed { i, m ->
            m.movie.id to Position(positionMs = (10 + i * 15) * 60_000L, lastPlayedAt = now - i * 3_600_000L)
        }
        val episodeId = shows().first().episodes[2].episode.id
        return (movieIds + (episodeId to Position(positionMs = 9 * 60_000L, lastPlayedAt = now - 1_800_000L))).toMap()
    }

    private fun movies(): List<MovieEntry> = (0 until MOVIE_COUNT).map { i ->
        val title = words[i % words.size] + " " + nouns[(i / words.size) % nouns.size] +
            if (i >= words.size * nouns.size) " ${i / (words.size * nouns.size) + 1}" else ""
        val year = 1960 + (i * 7) % 66
        val collection = if (i % 15 == 0) collections[(i / 15) % collections.size] else null
        val folder = "Movies/${collection ?: "_Singles"}/$title ($year)"
        MovieEntry(
            folder = folder,
            video = "movie.mp4",
            fingerprint = "fake",
            movie = Movie(
                id = "${collection ?: "_Singles"}/$title ($year)",
                title = title,
                year = year,
                collection = collection,
                collectionNumber = if (collection != null) i / 60 + 1 else null,
                genres = listOf(genres[i % genres.size], genres[(i * 3 + 1) % genres.size]).distinct(),
                runtimeMin = 80 + (i * 13) % 90,
                rating = 5.0 + (i % 40) / 10.0,
                contentRating = ratings[i % ratings.size],
                height = if (i % 3 == 0) 720 else 1080,
                bytes = (1_000_000_000L + i * 13_000_000L),
                sentAt = "2026-%02d-%02dT12:00:00".format(1 + i % 9, 1 + i % 28),
            ),
        )
    }

    private fun shows(): List<ShowEntry> = (0 until SHOW_COUNT).map { s ->
        val title = nouns[s % nouns.size] + " " + listOf("Road", "Street", "House", "Bay")[s % 4]
        val folder = "TV/$title"
        val episodes = (1..(1 + s % 3)).flatMap { season ->
            (1..8).map { ep ->
                val code = "S%02dE%02d".format(season, ep)
                EpisodeEntry(
                    folder = "$folder/Season %02d".format(season),
                    video = "$code.mp4",
                    fingerprint = "fake",
                    episode = Episode(id = "$title/$code", title = "Episode $ep", season = season, episode = ep, runtimeMin = 24 + s % 30),
                )
            }
        }
        ShowEntry(folder = folder, fingerprint = "fake", show = Show(id = title, title = title), episodes = episodes)
    }
}
