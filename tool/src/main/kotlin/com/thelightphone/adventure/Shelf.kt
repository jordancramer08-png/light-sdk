package com.thelightphone.adventure

import com.thelightphone.adventure.engine.Story
import com.thelightphone.adventure.engine.ZMachineError
import com.thelightphone.adventure.engine.storyFromBlorb
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A game the tool ships with. [asset] is its file in tool/src/main/assets. */
enum class BundledGame(val title: String, val asset: String) {
    ZORK1("Zork I", "zork1.dat"),
    ZORK2("Zork II", "zork2.dat"),
    ZORK3("Zork III", "zork3.dat"),
}

/** Where a game's file is. */
sealed interface GameSource {
    data class Bundled(val game: BundledGame) : GameSource
    data class Added(val file: File) : GameSource
}

/** One game on the shelf. [key] is the same identity its saves are filed under. */
data class ShelfEntry(
    val key: String,
    val title: String,
    val version: Int,
    val lastPlayed: Long?,
    val source: GameSource,
) {
    val removable: Boolean get() = source is GameSource.Added
}

/** The shelf screen's state. */
data class ShelfState(
    val entries: List<ShelfEntry> = emptyList(),
    val continueGame: ShelfEntry? = null,
    val unreadable: List<String> = emptyList(),
    val needsStorageAccess: Boolean = false,
    val removing: ShelfEntry? = null,
    val renaming: ShelfEntry? = null,
    val message: String? = null,
)

/** "my_great_game.z5" becomes "my great game". */
fun cleanName(fileName: String): String =
    fileName.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { fileName }

fun isStoryFile(file: File): Boolean = file.extension.lowercase() in STORY_EXTENSIONS

private val STORY_EXTENSIONS = setOf("z3", "z4", "z5", "z8", "zblorb", "zlb")

/** "Version 5, played Nov 6, 2026". */
fun shelfDetail(entry: ShelfEntry): String {
    val played = entry.lastPlayed?.let { "played " + DateFormat.getDateInstance().format(Date(it)) }
    return "Version ${entry.version}, ${played ?: "not played yet"}"
}

/** What the shelf remembers about each game: a custom name and when it was last played. */
class ShelfRecords(root: File) {
    private val folder = File(root, "shelf")
    private val lastGame = File(folder, "last.txt")

    private fun recordFile(key: String) = File(folder, "$key.txt")

    private fun lines(file: File): List<String> = try {
        file.readLines()
    } catch (error: IOException) {
        emptyList()
    }

    fun lastPlayed(key: String): Long? = lines(recordFile(key)).firstOrNull()?.toLongOrNull()

    fun customName(key: String): String? = lines(recordFile(key)).getOrNull(1)?.ifEmpty { null }

    private fun write(key: String, lastPlayed: Long?, name: String?) {
        write(recordFile(key), "${lastPlayed ?: ""}\n${name ?: ""}\n")
    }

    private fun write(file: File, text: String) {
        try {
            folder.mkdirs()
            file.writeText(text)
        } catch (error: IOException) {
            // Losing a name or a date must never stop a game from opening.
        }
    }

    fun rename(key: String, name: String?) = write(key, lastPlayed(key), name)

    fun markPlayed(key: String, time: Long) {
        write(key, time, customName(key))
        write(lastGame, key)
    }

    fun lastGameKey(): String? = lines(lastGame).firstOrNull()

    fun forget(key: String) {
        recordFile(key).delete()
        if (lastGameKey() == key) lastGame.delete()
    }
}

/** Finds the games and keeps the shelf's rules. [gamesFolder] is where added games live. */
class ShelfViewModel(
    private val readAsset: (String) -> ByteArray,
    private val gamesFolder: File,
    private val dataFolder: File,
    private val hasStorageAccess: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LightViewModel<Unit>() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val records = ShelfRecords(dataFolder)
    private val mutableState = MutableStateFlow(ShelfState())

    val state: StateFlow<ShelfState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) = refresh()

    override fun onCleared() = scope.cancel()

    /** Reads the shelf again, in the background. */
    fun refresh() {
        scope.launch {
            val unreadable = ArrayList<String>()
            val games = bundledEntries() + addedEntries(unreadable)
            val entries = games.distinctBy { it.key }.sortedBy { it.title.lowercase() }
            mutableState.update {
                it.copy(
                    entries = entries,
                    continueGame = entries.firstOrNull { entry -> entry.key == records.lastGameKey() },
                    unreadable = unreadable,
                    needsStorageAccess = !hasStorageAccess(),
                )
            }
        }
    }

    private fun bundledEntries() = BundledGame.entries.map { game ->
        entry(Story(readAsset(game.asset)), game.title, GameSource.Bundled(game))
    }

    private fun addedEntries(unreadable: MutableList<String>): List<ShelfEntry> {
        if (!hasStorageAccess()) return emptyList()
        val files = gamesFolder.listFiles()?.filter { it.isFile && isStoryFile(it) } ?: emptyList()
        return files.mapNotNull { file ->
            try {
                entry(Story(storyFromBlorb(file.readBytes())), cleanName(file.name), GameSource.Added(file))
            } catch (error: ZMachineError) {
                unreadable += file.name
                null
            } catch (error: IOException) {
                unreadable += file.name
                null
            }
        }
    }

    private fun entry(story: Story, defaultTitle: String, source: GameSource): ShelfEntry {
        val key = gameKey(story)
        val title = records.customName(key) ?: defaultTitle
        return ShelfEntry(key, title, story.header.version, records.lastPlayed(key), source)
    }

    /** The story file's bytes. Empty if the file has gone, which the game screen reports. */
    fun storyBytes(entry: ShelfEntry): ByteArray = when (val source = entry.source) {
        is GameSource.Bundled -> readAsset(source.game.asset)
        is GameSource.Added -> try {
            source.file.readBytes()
        } catch (error: IOException) {
            ByteArray(0)
        }
    }

    /** Remembers the game as the last one played, for Continue. */
    fun markPlayed(entry: ShelfEntry) = records.markPlayed(entry.key, clock())

    fun startRename(entry: ShelfEntry) = mutableState.update { it.copy(renaming = entry) }

    fun cancelRename() = mutableState.update { it.copy(renaming = null) }

    /** Saves the new name. An empty name goes back to the game's own. */
    fun finishRename(name: String) {
        val entry = mutableState.value.renaming ?: return
        records.rename(entry.key, name.trim().ifEmpty { null })
        cancelRename()
        refresh()
    }

    /** Asks for confirmation. Bundled games cannot be removed. */
    fun askToRemove(entry: ShelfEntry) {
        if (entry.removable) mutableState.update { it.copy(removing = entry, message = null) }
    }

    fun cancelRemove() = mutableState.update { it.copy(removing = null) }

    /** Deletes the game file, then its saves and records, and only if the file really went. */
    fun confirmRemove() {
        val entry = mutableState.value.removing ?: return
        val removed = deleteGameFile(entry)
        if (removed) {
            SaveStore(dataFolder, entry.key).deleteAll()
            records.forget(entry.key)
        }
        val message = if (removed) null else "Could not remove ${entry.title}"
        mutableState.update { it.copy(removing = null, message = message) }
        refresh()
    }

    private fun deleteGameFile(entry: ShelfEntry): Boolean {
        val file = (entry.source as? GameSource.Added)?.file ?: return false
        val inGamesFolder = file.canonicalFile.parentFile == gamesFolder.canonicalFile
        return inGamesFolder && file.delete()
    }
}
