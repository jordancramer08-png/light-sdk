package com.jordan.watch.storage

import android.util.Log
import com.jordan.watch.FeatureFlags
import com.jordan.watch.dev.DevLibrary
import com.jordan.watch.logic.Library
import com.jordan.watch.logic.LibraryIndexStore
import com.jordan.watch.logic.LibraryScanner
import com.jordan.watch.logic.Repositories
import com.jordan.watch.logic.StateStore
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
 * What the library screens show. [loaded] turns true once the saved index has been read;
 * [updating] is true while changed titles are read ("Updating library...").
 */
data class LibraryState(
    val library: Library = Library(),
    val loaded: Boolean = false,
    val updating: Boolean = false,
    /** The debug fake library is shown (FeatureFlags.FAKE_LIBRARY). */
    val fake: Boolean = false,
)

/**
 * The app-wide library and .state repositories. Screens call [refresh] every time they come
 * to the front (launch and resume). Everything happens in the background: the saved index
 * is shown first, then, only if /sdcard/Watch changed, the changed titles are read and the
 * library updates. The UI thread never waits on it. (Same shape as Listen's MusicLibrary.)
 */
object WatchLibrary {
    private const val TAG = "Watch"

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    /** Positions, watched marks, watchlist and settings. Loaded by the first [refresh]. */
    val repositories = Repositories(StateStore(WatchPaths.state))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Refresh requests; asking again while a refresh runs queues exactly one more. */
    private val requests = Channel<File>(Channel.CONFLATED)

    /** Run [work] on the library's background thread (e.g. saving a position). */
    fun inBackground(work: () -> Unit) {
        scope.launch {
            try {
                work()
            } catch (e: Exception) {
                Log.w(TAG, "Background work failed", e)
            }
        }
    }

    init {
        scope.launch {
            for (indexDir in requests) {
                try {
                    refreshNow(indexDir)
                } catch (e: Exception) {
                    Log.w(TAG, "Library refresh failed", e)
                    _state.update { it.copy(loaded = true, updating = false) }
                }
            }
        }
    }

    /**
     * Checks for a changed library in the background, if Watch may read its files.
     * [filesDir] is the app's own files folder, where the saved index lives.
     */
    fun refresh(filesDir: File) {
        if (FeatureFlags.FAKE_LIBRARY && showFakeLibrary()) return
        if (StorageAccess.hasAllFilesAccess()) requests.trySend(File(filesDir, "cache"))
    }

    /** Debug builds: puts the made-up library on screen once. False in release builds. */
    private fun showFakeLibrary(): Boolean {
        if (_state.value.fake) return true
        val index = DevLibrary.fakeIndex() ?: return false
        repositories.playback.useFake(DevLibrary.fakePositions())
        if (StorageAccess.hasAllFilesAccess()) inBackground { repositories.settings.load() }
        _state.value = LibraryState(library = Library(index), loaded = true, fake = true)
        return true
    }

    private fun refreshNow(indexDir: File) {
        val started = System.currentTimeMillis()
        // .state can change from the PC (Backup/Restore), so it's read again each time.
        repositories.load()

        val store = LibraryIndexStore(File(indexDir, "library_index.json"))
        val known = _state.value.library.index.takeIf { _state.value.loaded } ?: store.load().also { saved ->
            _state.update { it.copy(library = Library(saved), loaded = true) }
            Log.i(TAG, "Library: ${saved.movies.size} movies, ${saved.shows.size} shows from the index")
        }

        val scanner = LibraryScanner(WatchPaths.root, WatchPaths.lastSync)
        // Taken before the scan, so a change made during the scan is caught next time.
        val stamp = scanner.stamp()
        if (stamp == known.stamp) {
            Log.i(TAG, "Library: unchanged (checked in ${System.currentTimeMillis() - started} ms)")
            return
        }

        _state.update { it.copy(updating = true) }
        try {
            val updated = scanner.scan(known, stamp)
            _state.update { it.copy(library = Library(updated)) }
            PosterCache.clearMemory()
            store.save(updated)
            Log.i(TAG, "Library: ${updated.movies.size} movies after a rescan in ${System.currentTimeMillis() - started} ms")
        } finally {
            _state.update { it.copy(updating = false) }
        }
    }
}
