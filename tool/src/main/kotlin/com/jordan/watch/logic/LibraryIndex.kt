package com.jordan.watch.logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One movie found on the phone. [folder] is relative to /sdcard/Watch
 * ("Movies/_Singles/Up (2009)"); [video] is the video file's name in it. [fingerprint]
 * changes whenever the folder, video or movie.json changes, so only those are re-read.
 */
@Serializable
data class MovieEntry(
    val folder: String,
    val video: String,
    val fingerprint: String,
    val movie: Movie,
)

/** One episode file. [folder] is its season folder ("TV/Bluey/Season 01"). */
@Serializable
data class EpisodeEntry(
    val folder: String,
    val video: String,
    val fingerprint: String,
    val episode: Episode,
)

/** One show. [fingerprint] covers the show folder and show.json; episodes have their own. */
@Serializable
data class ShowEntry(
    val folder: String,
    val fingerprint: String,
    val show: Show,
    val episodes: List<EpisodeEntry> = emptyList(),
)

/**
 * The result of the last scan, saved in the app's own files so the library shows at once
 * on launch. [stamp] is [LibraryScanner.stamp] at the time of that scan.
 */
@Serializable
data class LibraryIndex(
    val version: Int = VERSION,
    val stamp: String = "",
    val movies: List<MovieEntry> = emptyList(),
    val shows: List<ShowEntry> = emptyList(),
) {
    companion object {
        /** Bump when the entries change meaning, so old saved indexes are thrown away. */
        const val VERSION = 1
    }
}

/** Reads and writes [LibraryIndex] as JSON in [file]. It can always be rebuilt by a scan. */
class LibraryIndexStore(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true }

    /** The saved index, or an empty one when it's missing, broken or from an older version. */
    fun load(): LibraryIndex {
        val text = AtomicFile.readTextOrNull(file) ?: return LibraryIndex()
        val index = try {
            json.decodeFromString(LibraryIndex.serializer(), text)
        } catch (e: Exception) {
            return LibraryIndex()
        }
        return if (index.version == LibraryIndex.VERSION) index else LibraryIndex()
    }

    fun save(index: LibraryIndex) {
        AtomicFile.writeText(file, json.encodeToString(LibraryIndex.serializer(), index))
    }
}

/** A collection's movies in story order. */
data class MovieCollection(val name: String, val movies: List<MovieEntry>)

/**
 * The library in memory, with quick lookups by id. Built once per scan (off the main
 * thread); the lookup tables are made the first time they're used.
 */
class Library(val index: LibraryIndex = LibraryIndex()) {

    val movies: List<MovieEntry> get() = index.movies
    val shows: List<ShowEntry> get() = index.shows

    private val moviesById: Map<String, MovieEntry> by lazy { movies.associateBy { it.movie.id } }
    private val showsById: Map<String, ShowEntry> by lazy { shows.associateBy { it.show.id } }
    private val episodesById: Map<String, Pair<ShowEntry, EpisodeEntry>> by lazy {
        shows.flatMap { show -> show.episodes.map { it.episode.id to (show to it) } }.toMap()
    }

    /** Collections A–Z, each in story order (collectionNumber, then year, then title). */
    val collections: List<MovieCollection> by lazy {
        movies.filter { it.movie.collection != null }
            .groupBy { it.movie.collection!! }
            .map { (name, entries) ->
                val ordered = NextUp.collectionOrder(entries.map { it.movie })
                MovieCollection(name, ordered.map { moviesById.getValue(it.id) })
            }
            .sortedBy { Sorting.sortTitle(it.name) }
    }

    fun movie(id: String): MovieEntry? = moviesById[id]
    fun show(id: String): ShowEntry? = showsById[id]

    /** The episode with [id] and the show it belongs to. */
    fun episode(id: String): Pair<ShowEntry, EpisodeEntry>? = episodesById[id]

    /** A show's season numbers, lowest first. */
    fun seasons(show: ShowEntry): List<Int> = show.episodes.map { it.episode.season }.distinct().sorted()

    fun episodesOf(show: ShowEntry, season: Int): List<EpisodeEntry> =
        show.episodes.filter { it.episode.season == season }

    /** The video file of a movie or episode, under [root] (/sdcard/Watch). */
    fun videoFile(root: File, folder: String, video: String): File = File(File(root, folder), video)

    val isEmpty: Boolean get() = movies.isEmpty() && shows.isEmpty()
}
