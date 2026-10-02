package com.thelightphone.listen.podcasts.store

import com.thelightphone.listen.storage.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The files of a downloaded episode, by name inside its show's folder
 * (/sdcard/Listen/Podcasts/<showId>/). [chapters] and [transcript] are there only when the
 * episode has them.
 */
@Serializable
data class DownloadedFiles(
    val audio: String,
    val bytes: Long = 0,
    val chapters: String? = null,
    val transcript: String? = null,
    /** The transcript's type as the feed gave it ("text/vtt"…), to read it the right way. */
    val transcriptType: String? = null,
    val downloadedAt: Long = 0,
) {
    val allFiles: List<String> get() = listOfNotNull(audio, chapters, transcript)
}

/**
 * What Jordan has done with one episode. Only episodes he has touched get a row, so the file
 * stays small however many episodes the feeds hold.
 */
@Serializable
data class EpisodeState(
    val positionMs: Long = 0,
    /** The length as the player found it (0 = not known yet). */
    val durationMs: Long = 0,
    val played: Boolean = false,
    val playedAt: Long? = null,
    val lastPlayedAt: Long? = null,
    val download: DownloadedFiles? = null,
)

/** podcast_episodes.json: every touched episode, keyed "showId/episodeId". */
@Serializable
data class EpisodeStatesData(val version: Int = 1, val episodes: Map<String, EpisodeState> = emptyMap())

/** An episode counts as finished in its last 30 seconds, or once 95% of it has played. */
const val PODCAST_FINISHED_WITHIN_MS = 30_000L
const val PODCAST_FINISHED_FRACTION = 0.95

fun isPodcastFinished(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0 && (durationMs - positionMs <= PODCAST_FINISHED_WITHIN_MS || positionMs >= durationMs * PODCAST_FINISHED_FRACTION)

fun episodeKey(showId: String, episodeId: String) = "$showId/$episodeId"

/** Reads and writes podcast_episodes.json; a broken file is moved aside, never overwritten. */
class EpisodeStatesFile(private val file: File) {

    fun load(): EpisodeStatesData {
        val text = AtomicFile.readTextOrNull(file) ?: return EpisodeStatesData()
        return try {
            json.decodeFromString(EpisodeStatesData.serializer(), text.removePrefix("﻿"))
        } catch (e: Exception) {
            file.renameTo(File(file.parentFile, "podcast_episodes.broken-${System.currentTimeMillis()}.json"))
            EpisodeStatesData()
        }
    }

    fun save(data: EpisodeStatesData) = AtomicFile.writeText(file, json.encodeToString(EpisodeStatesData.serializer(), data))

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = false
        }
    }
}

/**
 * Played marks, resume positions and downloads for every episode. Every change is written
 * straight away (atomically), so call from a background thread. The player saves positions
 * every 10 s and on pause/seek/background, as for audiobooks.
 */
class EpisodeStateStore(file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val store = EpisodeStatesFile(file)
    private val _states = MutableStateFlow(store.load().episodes)
    val states: StateFlow<Map<String, EpisodeState>> = _states.asStateFlow()

    fun get(showId: String, episodeId: String): EpisodeState = _states.value[episodeKey(showId, episodeId)] ?: EpisodeState()

    /**
     * Saves where playback is. Reaching the end ([isPodcastFinished]) marks the episode
     * played and returns true (so the caller can apply "after finishing: delete download").
     * [touch] moves "last played" on (false when saving a paused episode that hasn't moved,
     * so the rewind after a pause knows how long the pause really was).
     */
    @Synchronized
    fun savePosition(showId: String, episodeId: String, positionMs: Long, durationMs: Long, touch: Boolean = true): Boolean {
        val now = clock()
        var justFinished = false
        change(showId, episodeId) {
            val duration = if (durationMs > 0) durationMs else it.durationMs
            val finished = isPodcastFinished(positionMs, duration)
            justFinished = finished && !it.played
            it.copy(
                positionMs = if (finished) 0 else positionMs.coerceAtLeast(0),
                durationMs = duration,
                played = it.played || finished,
                playedAt = if (justFinished) now else it.playedAt,
                lastPlayedAt = if (touch || it.lastPlayedAt == null) now else it.lastPlayedAt,
            )
        }
        return justFinished
    }

    /** Mark Played: also forgets the resume position (the next play starts at the beginning). */
    @Synchronized
    fun markPlayed(showId: String, episodeId: String) {
        val now = clock()
        change(showId, episodeId) { if (it.played) it else it.copy(played = true, playedAt = now, positionMs = 0) }
    }

    @Synchronized
    fun markUnplayed(showId: String, episodeId: String) =
        change(showId, episodeId) { it.copy(played = false, playedAt = null) }

    /** "Mark all played" on New Episodes: one write for all of them. */
    @Synchronized
    fun markAllPlayed(keys: List<Pair<String, String>>) {
        if (keys.isEmpty()) return
        val now = clock()
        val map = _states.value.toMutableMap()
        for ((show, ep) in keys) {
            val k = episodeKey(show, ep)
            val s = map[k] ?: EpisodeState()
            if (!s.played) map[k] = s.copy(played = true, playedAt = now, positionMs = 0)
        }
        save(map)
    }

    @Synchronized
    fun setDownloaded(showId: String, episodeId: String, files: DownloadedFiles) =
        change(showId, episodeId) { it.copy(download = files) }

    /** After "Remove download" (or delete-after-finishing): the episode stays, the files are gone. */
    @Synchronized
    fun clearDownload(showId: String, episodeId: String) =
        change(showId, episodeId) { it.copy(download = null) }

    /** Every downloaded episode of [showId] (for "delete this show's downloads" on unfollow). */
    fun downloadsOf(showId: String): Map<String, DownloadedFiles> =
        _states.value.filterKeys { it.startsWith("$showId/") }
            .mapNotNull { (k, v) -> v.download?.let { k.substringAfter('/') to it } }.toMap()

    /** All podcast downloads' sizes added up (the "storage used" line). */
    fun downloadedBytes(): Long = _states.value.values.sumOf { it.download?.bytes ?: 0 }

    private fun change(showId: String, episodeId: String, update: (EpisodeState) -> EpisodeState) {
        val key = episodeKey(showId, episodeId)
        val before = _states.value[key] ?: EpisodeState()
        val after = update(before)
        if (after == before) return
        val map = _states.value.toMutableMap()
        if (after == EpisodeState()) map.remove(key) else map[key] = after
        save(map)
    }

    private fun save(map: Map<String, EpisodeState>) {
        store.save(EpisodeStatesData(episodes = map))
        _states.value = map
    }
}
