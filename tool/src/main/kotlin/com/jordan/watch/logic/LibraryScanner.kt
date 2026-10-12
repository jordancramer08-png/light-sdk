package com.jordan.watch.logic

import java.io.File
import java.util.zip.CRC32

/** The video formats Watch looks for. */
val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "m4v", "mov", "webm")

/**
 * Finds the movies and shows under [root] (/sdcard/Watch) and reads their json, re-reading
 * only titles whose folder, video or json changed since [LibraryIndex] was saved. Folders
 * and files starting with "." are skipped (.state, half-sent ".part" work). Never looks
 * outside Movies/ and TV/. Blocking: run off the main thread.
 *
 * The readers are parameters only so tests can count how often they run.
 */
class LibraryScanner(
    private val root: File,
    private val lastSyncFile: File = File(root, ".state/last-sync.txt"),
    private val readMovie: (File, String) -> Movie = MediaJson::readMovie,
    private val readShow: (File) -> Show = MediaJson::readShow,
    private val readEpisode: (File, String) -> Episode = MediaJson::readEpisode,
) {
    private val moviesDir = File(root, "Movies")
    private val tvDir = File(root, "TV")

    /**
     * A cheap fingerprint of the library: last-sync.txt plus every folder's change time (a
     * folder's time changes when something is added to it or removed). If it matches the
     * saved index's, nothing changed and no scan is needed. (Same idea as Listen.)
     */
    fun stamp(): String {
        val sync = AtomicFile.readTextOrNull(lastSyncFile)?.trim().orEmpty()
        val folders = (folderTimes(moviesDir) + folderTimes(tvDir)).sorted()
        val crc = CRC32().apply { update(folders.joinToString("\n").toByteArray()) }
        return "sync=$sync;folders=${folders.size};crc=${crc.value}"
    }

    /** The library now on the phone. Unchanged titles are kept from [previous]. */
    fun scan(previous: LibraryIndex, stamp: String = stamp()): LibraryIndex {
        val oldMovies = previous.movies.associateBy { it.folder }
        val oldShows = previous.shows.associateBy { it.folder }
        return LibraryIndex(
            stamp = stamp,
            movies = scanMovies(oldMovies),
            shows = scanShows(oldShows),
        )
    }

    // ---- Movies: Movies/<Collection or _Singles>/<Title (Year)>/ ----

    private fun scanMovies(old: Map<String, MovieEntry>): List<MovieEntry> =
        subfolders(moviesDir).flatMap { group -> subfolders(group) }
            .mapNotNull { folder -> movieEntry(folder, old[relative(folder)]) }

    private fun movieEntry(folder: File, old: MovieEntry?): MovieEntry? {
        val json = File(folder, "movie.json")
        if (old != null && fingerprint(folder, File(folder, old.video), json) == old.fingerprint) return old
        val video = movieVideo(folder, json) ?: return null
        val print = fingerprint(folder, video, json)
        val id = relative(folder).removePrefix("Movies/")
        return MovieEntry(relative(folder), video.name, print, readMovie(folder, id))
    }

    /** The file movie.json names, if it's there; otherwise the first video file A–Z. */
    private fun movieVideo(folder: File, json: File): File? {
        val named = MediaJson.videoFileName(json)?.let { File(folder, it) }
        if (named != null && named.isFile) return named
        return videoFiles(folder).firstOrNull()
    }

    // ---- TV: TV/<Show>/Season 01/S01E01.mp4 ----

    private fun scanShows(old: Map<String, ShowEntry>): List<ShowEntry> =
        subfolders(tvDir).mapNotNull { folder -> showEntry(folder, old[relative(folder)]) }

    private fun showEntry(folder: File, old: ShowEntry?): ShowEntry? {
        val json = File(folder, "show.json")
        val print = fingerprint(folder, null, json)
        val show = if (old != null && old.fingerprint == print) old.show else readShow(folder)
        val oldEpisodes = old?.episodes.orEmpty().associateBy { it.folder + "/" + it.video }
        val episodes = subfolders(folder).flatMap { season ->
            videoFiles(season).map { video ->
                episodeEntry(season, video, show.id, oldEpisodes[relative(season) + "/" + video.name])
            }
        }
        if (episodes.isEmpty()) return null
        return ShowEntry(relative(folder), print, show, sortEpisodes(episodes))
    }

    private fun episodeEntry(season: File, video: File, showId: String, old: EpisodeEntry?): EpisodeEntry {
        val json = File(season, video.nameWithoutExtension + ".json")
        val print = fingerprint(null, video, json)
        if (old != null && old.fingerprint == print) return old
        return EpisodeEntry(relative(season), video.name, print, readEpisode(json, showId))
    }

    private fun sortEpisodes(episodes: List<EpisodeEntry>): List<EpisodeEntry> =
        episodes.sortedWith(compareBy({ it.episode.season }, { it.episode.episode }, { it.video }))

    // ---- Files ----

    private fun subfolders(dir: File): List<File> =
        dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name }.orEmpty()

    private fun videoFiles(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in VIDEO_EXTENSIONS }
            ?.sortedBy { it.name }
            .orEmpty()

    /** "path=time" for [dir] and every folder below it, skipping hidden ones. */
    private fun folderTimes(dir: File): List<String> {
        if (!dir.isDirectory) return listOf(relative(dir) + "=missing")
        return dir.walkTopDown()
            .onEnter { it == dir || !it.name.startsWith(".") }
            .filter { it.isDirectory }
            .map { relative(it) + "=" + it.lastModified() }
            .toList()
    }

    /** Size and change time of each file that matters; any change means "read it again". */
    private fun fingerprint(folder: File?, video: File?, json: File): String = listOfNotNull(
        folder?.let { "d" + it.lastModified() },
        video?.let { "v" + it.length() + "@" + it.lastModified() },
        if (json.isFile) "j" + json.length() + "@" + json.lastModified() else "j-",
    ).joinToString(";")

    private fun relative(file: File): String = file.relativeTo(root).invariantSeparatorsPath
}
