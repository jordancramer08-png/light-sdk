package com.jordan.watch.logic

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** Where Jordan stopped in one title. Keyed by the title's `id` in positions.json. */
@Serializable
data class Position(
    val positionMs: Long = 0,
    val speed: Float = 1f,
    /** Epoch milliseconds of the last time this title played. */
    val lastPlayedAt: Long = 0,
)

/** Watch's settings. Missing fields get these defaults; unknown ones are ignored. */
@Serializable
data class WatchSettings(
    val version: Int = 1,
    /** "DARK" or "LIGHT"; anything unknown reads as dark. */
    val theme: String = "DARK",
    val gridColumns: Int = 3,
    /** "GRID" (posters) or "LIST" (rows with a small poster). */
    val libraryView: String = "GRID",
    val skipSeconds: Int = 15,
    /** "PORTRAIT", "LANDSCAPE" or "LAST". */
    val startIn: String = "PORTRAIT",
    val lastOrientation: String = "PORTRAIT",
    /** "SMALL", "MEDIUM", "LARGE" or "XLARGE". */
    val subtitleSize: String = "MEDIUM",
    /** Each tab's sort and filters, by [BrowseTab] name. */
    val tabViews: Map<String, TabView> = emptyMap(),
) {
    fun tabView(tab: BrowseTab): TabView = tabViews[tab.name] ?: TabView()
}

/**
 * The four files in /sdcard/Watch/.state/. Every write is atomic, and a missing or broken
 * file reads as empty (or default settings) so the app never crashes over it.
 */
class StateStore(private val dir: File) {

    fun loadPositions(): Map<String, Position> = read(POSITIONS, positionsSerializer) ?: emptyMap()
    fun savePositions(positions: Map<String, Position>) = write(POSITIONS, positionsSerializer, positions)

    /** Watched title ids, with the epoch milliseconds they were marked watched. */
    fun loadWatched(): Map<String, Long> = read(WATCHED, watchedSerializer) ?: emptyMap()
    fun saveWatched(watched: Map<String, Long>) = write(WATCHED, watchedSerializer, watched)

    /** Title ids in the order they were added. */
    fun loadWatchlist(): List<String> = read(WATCHLIST, watchlistSerializer) ?: emptyList()
    fun saveWatchlist(ids: List<String>) = write(WATCHLIST, watchlistSerializer, ids)

    /** Movie ids Jordan chose "Remove from phone" for; the PC script deletes them next sync. */
    fun loadRemovals(): List<String> = read(REMOVALS, watchlistSerializer) ?: emptyList()
    fun saveRemovals(ids: List<String>) = write(REMOVALS, watchlistSerializer, ids)

    fun loadSettings(): WatchSettings = read(SETTINGS, WatchSettings.serializer()) ?: WatchSettings()
    fun saveSettings(settings: WatchSettings) = write(SETTINGS, WatchSettings.serializer(), settings)

    private fun <T> read(name: String, serializer: KSerializer<T>): T? {
        val text = AtomicFile.readTextOrNull(File(dir, name)) ?: return null
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: Exception) {
            null
        }
    }

    private fun <T> write(name: String, serializer: KSerializer<T>, value: T) {
        AtomicFile.writeText(File(dir, name), json.encodeToString(serializer, value))
    }

    private companion object {
        const val POSITIONS = "positions.json"
        const val WATCHED = "watched.json"
        const val WATCHLIST = "watchlist.json"
        const val SETTINGS = "settings.json"
        const val REMOVALS = "remove-requests.json"

        val positionsSerializer = MapSerializer(String.serializer(), Position.serializer())
        val watchedSerializer = MapSerializer(String.serializer(), Long.serializer())
        val watchlistSerializer = ListSerializer(String.serializer())
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    }
}
