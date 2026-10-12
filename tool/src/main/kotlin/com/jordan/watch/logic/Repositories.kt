package com.jordan.watch.logic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * The four .state files, each held in memory as a StateFlow the screens can watch. A
 * change updates the flow at once and then writes the file (atomically, through
 * [StateStore]). Reading and writing touch the disk: call [load] and the changes off the
 * main thread. A write that fails keeps the change in memory; the next one retries it.
 */

/** Where Jordan stopped in each title, and which titles he has watched. */
class PlaybackRepository(private val store: StateStore) {

    private val _positions = MutableStateFlow<Map<String, Position>>(emptyMap())
    val positions: StateFlow<Map<String, Position>> = _positions.asStateFlow()

    private val _watched = MutableStateFlow<Map<String, Long>>(emptyMap())
    val watched: StateFlow<Map<String, Long>> = _watched.asStateFlow()

    /** True while the debug fake library is shown: nothing is read from or written to .state. */
    @Volatile
    private var fake = false

    @Synchronized
    fun load() {
        if (fake) return
        _positions.value = store.loadPositions()
        _watched.value = store.loadWatched()
    }

    /** Debug fake library only: shows [positions] and never touches the real .state files. */
    @Synchronized
    fun useFake(positions: Map<String, Position>) {
        fake = true
        _positions.value = positions
        _watched.value = emptyMap()
    }

    fun position(id: String): Position? = _positions.value[id]
    fun isWatched(id: String): Boolean = id in _watched.value

    /**
     * Saves where he is in [id]. Reaching the end (see [Resume.isWatched]) also marks it
     * watched. [durationMs] may be 0 when the player doesn't know it yet.
     */
    @Synchronized
    fun savePosition(id: String, positionMs: Long, durationMs: Long, speed: Float, nowMs: Long) {
        _positions.value = _positions.value + (id to Position(positionMs.coerceAtLeast(0), speed, nowMs))
        if (!fake) tryWrite { store.savePositions(_positions.value) }
        if (Resume.isWatched(positionMs, durationMs)) markWatched(id, nowMs)
    }

    /** Marks [id] watched. Its position and speed are kept (speed is remembered per title). */
    @Synchronized
    fun markWatched(id: String, nowMs: Long) {
        if (id in _watched.value) return
        _watched.value = _watched.value + (id to nowMs)
        if (!fake) tryWrite { store.saveWatched(_watched.value) }
    }

    /** Unmarks [id] and starts it from the beginning next time. */
    @Synchronized
    fun markUnwatched(id: String) {
        _watched.value = _watched.value - id
        if (!fake) tryWrite { store.saveWatched(_watched.value) }
        val old = _positions.value[id] ?: return
        _positions.value = _positions.value + (id to old.copy(positionMs = 0))
        if (!fake) tryWrite { store.savePositions(_positions.value) }
    }

    /** The state the library rows and filters use. */
    fun watchState(id: String): WatchState = when {
        isWatched(id) -> WatchState.Watched
        (position(id)?.positionMs ?: 0) > 0 -> WatchState.InProgress
        else -> WatchState.Unwatched
    }

    /** Started but not finished, most recently played first (the Continue Watching row). */
    fun inProgressIds(): List<String> =
        _positions.value.filter { (id, p) -> p.positionMs > 0 && id !in _watched.value }
            .entries.sortedByDescending { it.value.lastPlayedAt }
            .map { it.key }
}

/** Titles Jordan wants to watch, in the order he added them. */
class WatchlistRepository(private val store: StateStore) {

    private val _ids = MutableStateFlow<List<String>>(emptyList())
    val ids: StateFlow<List<String>> = _ids.asStateFlow()

    @Synchronized
    fun load() {
        _ids.value = store.loadWatchlist()
    }

    fun contains(id: String): Boolean = id in _ids.value

    @Synchronized
    fun add(id: String) {
        if (id in _ids.value) return
        _ids.value = _ids.value + id
        tryWrite { store.saveWatchlist(_ids.value) }
    }

    @Synchronized
    fun remove(id: String) {
        if (id !in _ids.value) return
        _ids.value = _ids.value - id
        tryWrite { store.saveWatchlist(_ids.value) }
    }

    fun toggle(id: String) = if (contains(id)) remove(id) else add(id)
}

/**
 * "Remove from phone" requests. The app never deletes video files (CLAUDE.md 4); it lists
 * the ids in .state/remove-requests.json, hides them from the library at once, and the
 * PC script deletes them on the next sync (keeping watched marks and positions).
 */
class RemovalRepository(private val store: StateStore) {

    private val _ids = MutableStateFlow<List<String>>(emptyList())
    val ids: StateFlow<List<String>> = _ids.asStateFlow()

    @Volatile
    private var fake = false

    @Synchronized
    fun load() {
        if (!fake) _ids.value = store.loadRemovals()
    }

    /** Debug fake library only: requests stay in memory. */
    fun useFake() {
        fake = true
    }

    @Synchronized
    fun request(id: String) {
        if (id in _ids.value) return
        _ids.value = _ids.value + id
        if (!fake) tryWrite { store.saveRemovals(_ids.value) }
    }

    @Synchronized
    fun cancel(id: String) {
        if (id !in _ids.value) return
        _ids.value = _ids.value - id
        if (!fake) tryWrite { store.saveRemovals(_ids.value) }
    }
}

/** Watch's settings. */
class SettingsRepository(private val store: StateStore) {

    private val _settings = MutableStateFlow(WatchSettings())
    val settings: StateFlow<WatchSettings> = _settings.asStateFlow()

    @Synchronized
    fun load() {
        _settings.value = store.loadSettings()
    }

    /** Changes settings, e.g. `update { it.copy(gridColumns = 2) }`. */
    @Synchronized
    fun update(change: (WatchSettings) -> WatchSettings) {
        val next = change(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        tryWrite { store.saveSettings(next) }
    }
}

/** All four, made from one .state folder. */
class Repositories(store: StateStore) {
    val playback = PlaybackRepository(store)
    val watchlist = WatchlistRepository(store)
    val settings = SettingsRepository(store)
    val removals = RemovalRepository(store)

    fun load() {
        removals.load()
        playback.load()
        watchlist.load()
        settings.load()
    }
}

/** A full card or a removed SD folder must not crash Watch; the change stays in memory. */
private inline fun tryWrite(write: () -> Unit) {
    try {
        write()
    } catch (e: Exception) {
        // Kept in memory; the next change writes the whole file again.
    }
}
