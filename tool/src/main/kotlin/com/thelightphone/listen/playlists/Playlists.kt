package com.thelightphone.listen.playlists

import android.util.Log
import com.thelightphone.listen.storage.AtomicFile
import com.thelightphone.listen.storage.ListenPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Reads and writes playlists.json. A missing file reads as no playlists. A file that can't
 * be read is moved aside to `playlists.broken-<time>.json` first, so the next save can
 * never write over playlists that could still be rescued by hand.
 */
class PlaylistsFile(private val file: File) {

    val lastModified: Long get() = file.lastModified()

    fun load(): PlaylistsData {
        val text = AtomicFile.readTextOrNull(file) ?: return PlaylistsData()
        return try {
            json.decodeFromString(PlaylistsData.serializer(), text)
        } catch (e: Exception) {
            file.renameTo(File(file.parentFile, "playlists.broken-${System.currentTimeMillis()}.json"))
            PlaylistsData()
        }
    }

    fun save(data: PlaylistsData) {
        AtomicFile.writeText(file, encode(data))
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun encode(data: PlaylistsData): String = json.encodeToString(PlaylistsData.serializer(), data)
    }
}

/**
 * The app-wide playlists, saved in /sdcard/Listen/.state/playlists.json (so an uninstall
 * can't lose them and the PC script backs them up). Every screen calls [load] when it shows;
 * the file is read again only if something else changed it (a restore from the PC).
 * Changes show at once and are saved in the background. Nothing changes before the file has
 * been read, so an empty list is never saved over real playlists.
 */
object Playlists {
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** One thread for reading and writing the file, so they never overlap. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val file by lazy { PlaylistsFile(File(ListenPaths.state, "playlists.json")) }

    /** The file's change time when Listen last read or wrote it (only used on [scope]'s thread). */
    private var knownModified = -1L

    fun load() {
        scope.launch {
            val modified = file.lastModified
            if (_loaded.value && modified == knownModified) return@launch
            val data = file.load()
            knownModified = file.lastModified
            _playlists.value = data.playlists
            _loaded.value = true
        }
    }

    fun playlist(id: String): Playlist? = _playlists.value.firstOrNull { it.id == id }

    /** Makes an empty playlist and returns its id. */
    fun create(name: String): String {
        val id = UUID.randomUUID().toString()
        change { it + Playlist(id = id, name = name) }
        return id
    }

    fun rename(id: String, name: String) = changePlaylist(id) { it.copy(name = name) }

    fun delete(id: String) = change { list -> list.filterNot { it.id == id } }

    /** Adds [entry] to the playlist, or takes it out if the same music is already there. */
    fun toggle(id: String, entry: PlaylistEntry) =
        changePlaylist(id) { if (it.contains(entry)) it.removing(entry) else it.adding(entry) }

    fun add(id: String, entry: PlaylistEntry) = changePlaylist(id) { it.adding(entry) }

    /** Removes the entry at [index], if it's still [expected] (so a tap never hits the wrong row). */
    fun removeAt(id: String, index: Int, expected: PlaylistEntry) =
        changePlaylist(id) { if (it.entries.getOrNull(index) == expected) it.removingAt(index) else it }

    /** Moves the entry at [index] up or down one place, if it's still [expected]. */
    fun move(id: String, index: Int, expected: PlaylistEntry, up: Boolean) =
        changePlaylist(id) { if (it.entries.getOrNull(index) == expected) it.moving(index, up) else it }

    private fun changePlaylist(id: String, update: (Playlist) -> Playlist) =
        change { list -> list.map { if (it.id == id) update(it) else it } }

    private fun change(update: (List<Playlist>) -> List<Playlist>) {
        if (!_loaded.value) return
        _playlists.update(update)
        scope.launch { write() }
    }

    private fun write() {
        try {
            file.save(PlaylistsData(playlists = _playlists.value))
            knownModified = file.lastModified
        } catch (e: Exception) {
            Log.w("Listen", "Couldn't save playlists.json", e)
        }
    }
}
