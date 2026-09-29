package com.thelightphone.listen.music

import android.util.Log
import com.thelightphone.listen.storage.ListenPaths
import com.thelightphone.listen.storage.StorageAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * What the music screens show. [songs] are sorted A–Z by title; [albums] and [artists] are
 * grouped from them (in no particular order: each screen sorts its own list). [loaded] turns
 * true once the saved index has been read; [updating] is true while changed files are read.
 */
data class MusicLibraryState(
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val loaded: Boolean = false,
    val updating: Boolean = false,
) {
    private val albumsByKey: Map<String, Album> by lazy { albums.associateBy { it.key } }
    private val artistsByKey: Map<String, Artist> by lazy { artists.associateBy { it.key } }

    fun album(key: String): Album? = albumsByKey[key]
    fun artist(key: String): Artist? = artistsByKey[key]

    /** The album [song] is on, or null if it isn't in the library (any more). */
    fun albumOf(song: Song): Album? = albumsByKey[song.albumKey]
}

/**
 * The app-wide music library. Screens call [refresh] every time they come to the front
 * (launch and resume). All work happens in the background: the saved index is shown
 * first, then, only if the music folder changed, the changed files are read and the
 * lists update. The UI thread never waits on it.
 */
object MusicLibrary {
    private val _state = MutableStateFlow(MusicLibraryState())
    val state: StateFlow<MusicLibraryState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Refresh requests; asking again while a refresh runs queues exactly one more. */
    private val requests = Channel<File>(Channel.CONFLATED)

    private var index: MusicIndex? = null

    init {
        scope.launch {
            for (indexDir in requests) {
                try {
                    refreshNow(indexDir)
                } catch (e: Exception) {
                    Log.w("Listen", "Music refresh failed", e)
                    _state.update { it.copy(loaded = true, updating = false) }
                }
            }
        }
    }

    /**
     * Checks for changed music in the background, if Listen may read its files. [filesDir]
     * is the app's own files folder, where the index cache lives.
     */
    fun refresh(filesDir: File) {
        if (StorageAccess.hasAllFilesAccess()) requests.trySend(File(filesDir, "cache"))
    }

    private fun refreshNow(indexDir: File) {
        val store = MusicIndexStore(File(indexDir, "music_index.json"))
        val known = index ?: store.load().also { saved ->
            index = saved
            publish(saved.songs)
        }

        val scanner = MusicScanner(ListenPaths.music, ListenPaths.lastSync, TagReader::read)
        // Taken before the scan, so a change made during the scan is caught next time.
        val stamp = scanner.stamp()
        if (stamp == known.stamp) return

        _state.update { it.copy(updating = true) }
        try {
            val songs = scanner.scan(known.songs, onProgress = ::publish)
            val updated = MusicIndex(stamp = stamp, songs = songs)
            index = updated
            publish(songs)
            store.save(updated)
        } finally {
            _state.update { it.copy(updating = false) }
        }
    }

    /** Sorts and groups in the background (this runs on the library's IO thread), then shows. */
    private fun publish(songs: List<Song>) {
        val sorted = sortedByTitle(songs)
        val albums = groupAlbums(sorted)
        val artists = groupArtists(albums)
        _state.update { it.copy(songs = sorted, albums = albums, artists = artists, loaded = true) }
    }
}
