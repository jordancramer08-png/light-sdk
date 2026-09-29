package com.thelightphone.listen.playback

import com.thelightphone.listen.storage.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Where a music queue came from. [kind] is "songs" (all songs), and later "album",
 * "artist" or "playlist"; [key] names which one ("" for all songs).
 */
@Serializable
data class QueueSource(val kind: String = KIND_SONGS, val key: String = "") {
    companion object {
        const val KIND_SONGS = "songs"
    }
}

/**
 * The music resume point saved in /sdcard/Listen/.state/music_state.json. [paths] are the
 * queue in its own (unshuffled) order, relative to /sdcard/Listen/Music; [index] points
 * into it. [repeat] is "off", "all" or "one". Missing fields get these defaults, so an
 * older or hand-edited file still loads.
 */
@Serializable
data class MusicState(
    val version: Int = 1,
    val source: QueueSource = QueueSource(),
    val paths: List<String> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: String = REPEAT_OFF,
    val savedAt: Long = 0,
) {
    companion object {
        const val REPEAT_OFF = "off"
        const val REPEAT_ALL = "all"
        const val REPEAT_ONE = "one"
    }
}

/**
 * A saved state, trimmed to the songs still on the phone ([exists] says whether a path is).
 * Keeps the saved song and its position when that song is still there; if it was removed,
 * starts at the song that followed it, from the beginning. Null when nothing is left.
 */
fun MusicState.restorable(exists: (String) -> Boolean): MusicState? {
    val kept = paths.filter(exists)
    if (kept.isEmpty()) return null
    val saved = index.coerceIn(0, paths.lastIndex.coerceAtLeast(0))
    val savedPath = paths.getOrNull(saved)
    if (savedPath != null && exists(savedPath)) {
        return copy(paths = kept, index = kept.indexOf(savedPath), positionMs = positionMs.coerceAtLeast(0))
    }
    // The saved song is gone: count the kept songs that came before it.
    val next = paths.take(saved).count(exists).coerceAtMost(kept.lastIndex)
    return copy(paths = kept, index = next, positionMs = 0)
}

/** Reads and writes music_state.json. A missing or broken file reads as null, never a crash. */
class MusicStateStore(private val file: File) {

    fun load(): MusicState? {
        val text = AtomicFile.readTextOrNull(file) ?: return null
        return try {
            json.decodeFromString(MusicState.serializer(), text)
        } catch (e: Exception) {
            null
        }
    }

    fun save(state: MusicState) {
        AtomicFile.writeText(file, encode(state))
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }

        fun encode(state: MusicState): String = json.encodeToString(MusicState.serializer(), state)
    }
}
