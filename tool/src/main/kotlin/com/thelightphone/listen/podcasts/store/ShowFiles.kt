package com.thelightphone.listen.podcasts.store

import com.thelightphone.listen.podcasts.feed.Episode
import com.thelightphone.listen.podcasts.feed.ParsedFeed
import com.thelightphone.listen.podcasts.feed.ShowInfo
import com.thelightphone.listen.storage.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** feed.json: a show's episode list as last fetched, without the long descriptions. */
@Serializable
data class FeedSnapshot(
    val version: Int = 1,
    val fetchedAt: Long = 0,
    val show: ShowInfo = ShowInfo(title = ""),
    val episodes: List<Episode> = emptyList(),
)

/** notes.json: the show's and every episode's full description, as readable text. */
@Serializable
data class FeedNotes(val version: Int = 1, val show: String = "", val episodes: Map<String, String> = emptyMap())

/**
 * One show's folder, /sdcard/Listen/Podcasts/<showId>/: feed.json, notes.json, art and
 * downloads. Everything here can be rebuilt (by Refresh or a new download), so it's kept out
 * of .state/ and out of backups. The list and the notes are separate files so a show page
 * opens fast and the notes are read only when one is shown.
 */
class ShowFiles(val dir: File) {
    private val feedFile = File(dir, "feed.json")
    private val notesFile = File(dir, "notes.json")

    /** Saves a freshly fetched feed (the list, then the notes). */
    fun save(feed: ParsedFeed, fetchedAt: Long) {
        AtomicFile.writeText(
            feedFile,
            json.encodeToString(FeedSnapshot.serializer(), FeedSnapshot(fetchedAt = fetchedAt, show = feed.show, episodes = feed.episodes)),
        )
        AtomicFile.writeText(
            notesFile,
            json.encodeToString(FeedNotes.serializer(), FeedNotes(show = feed.showNotes, episodes = feed.episodeNotes)),
        )
    }

    /** The saved list, or null when there's none yet (or it can't be read: Refresh rebuilds it). */
    fun loadSnapshot(): FeedSnapshot? = read(feedFile) { json.decodeFromString(FeedSnapshot.serializer(), it) }

    fun loadNotes(): FeedNotes = read(notesFile) { json.decodeFromString(FeedNotes.serializer(), it) } ?: FeedNotes()

    /** A file in this show's folder (art, downloads). */
    fun file(name: String) = File(dir, name)

    /** Bytes used by every file in the folder. */
    fun bytesUsed(): Long = dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    private fun <T> read(file: File, decode: (String) -> T): T? {
        val text = AtomicFile.readTextOrNull(file) ?: return null
        return try {
            decode(text)
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = false
        }
    }
}
