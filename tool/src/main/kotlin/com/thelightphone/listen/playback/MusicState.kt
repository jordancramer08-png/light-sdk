package com.thelightphone.listen.playback

import com.thelightphone.listen.storage.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Where a music queue came from. [kind] is "songs" (all songs), "album", "artist" or
 * "playlist"; [key] names which one ("" for all songs, else the album's or artist's key, or
 * the playlist's id).
 */
@Serializable
data class QueueSource(val kind: String = KIND_SONGS, val key: String = "") {
    companion object {
        const val KIND_SONGS = "songs"
        const val KIND_ALBUM = "album"
        const val KIND_ARTIST = "artist"
        const val KIND_PLAYLIST = "playlist"
        const val KIND_GENRE = "genre"
    }
}

/**
 * The music resume point saved in /sdcard/Listen/.state/music_state.json. [paths] are the
 * queue in the order it plays (shuffled when [shuffle] is on, with any Play next / Add to
 * queue changes), relative to /sdcard/Listen/Music; [index] points into it. While shuffled,
 * [unshuffledPaths] is the order turning shuffle off goes back to. (A version-1 file with
 * shuffle on kept the unshuffled order in [paths]; it's shuffled again when it loads.) [repeat] is "off", "all" or "one". [book] is the id of the audiobook loaded on
 * top of this music spot (its own place is in book_positions.json), so Listen reopens the
 * book, or null when music was last loaded. Missing fields get these defaults, so an
 * older or hand-edited file still loads.
 */
@Serializable
data class MusicState(
    val version: Int = VERSION,
    val source: QueueSource = QueueSource(),
    val paths: List<String> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: String = REPEAT_OFF,
    val savedAt: Long = 0,
    val book: String? = null,
    val unshuffledPaths: List<String>? = null,
) {
    companion object {
        const val VERSION = 2
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
    val unshuffled = unshuffledPaths?.filter(exists)
    val saved = index.coerceIn(0, paths.lastIndex.coerceAtLeast(0))
    val savedPath = paths.getOrNull(saved)
    if (savedPath != null && exists(savedPath)) {
        return copy(paths = kept, index = paths.take(saved).count(exists), positionMs = positionMs.coerceAtLeast(0), unshuffledPaths = unshuffled)
    }
    // The saved song is gone: count the kept songs that came before it.
    val next = paths.take(saved).count(exists).coerceAtMost(kept.lastIndex)
    return copy(paths = kept, index = next, positionMs = 0, unshuffledPaths = unshuffled)
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

/**
 * [added] paths put into a saved spot (while an audiobook plays, music's queue waits here):
 * right after the current song ([next]) or at the end.
 */
fun MusicState.adding(added: List<String>, next: Boolean): MusicState {
    if (paths.isEmpty()) return copy(paths = added, index = 0, positionMs = 0, unshuffledPaths = null)
    val at = if (next) (index + 1).coerceIn(0, paths.size) else paths.size
    val base = unshuffledPaths
    val unshuffled = base?.let {
        if (!next) it + added else {
            val here = it.indexOf(paths.getOrNull(index))
            val after = if (here >= 0) here + 1 else it.size
            it.take(after) + added + it.drop(after)
        }
    }
    return copy(paths = paths.take(at) + added + paths.drop(at), unshuffledPaths = unshuffled)
}
