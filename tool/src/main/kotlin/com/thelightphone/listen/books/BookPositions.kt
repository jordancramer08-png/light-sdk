package com.thelightphone.listen.books

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Where one book is: file [fileIndex] (into the book's files) at [positionMs] in that file,
 * its [speed], when it was last played (ms since 1970, 0 = never) and whether it's [finished].
 */
@Serializable
data class BookPosition(
    val fileIndex: Int = 0,
    val positionMs: Long = 0,
    val speed: Float = 1f,
    val lastPlayedAt: Long = 0,
    val finished: Boolean = false,
)

/** book_positions.json: every book's position, keyed by book id. */
@Serializable
data class BookPositionsData(
    val version: Int = 1,
    val books: Map<String, BookPosition> = emptyMap(),
)

/**
 * Reads and writes book_positions.json. A missing file reads as no positions. A file that
 * can't be read is moved aside to `book_positions.broken-<time>.json` first, so a save never
 * writes over places that could still be rescued by hand.
 */
class BookPositionsFile(private val file: File) {

    val lastModified: Long get() = file.lastModified()

    fun load(): BookPositionsData {
        val text = AtomicFile.readTextOrNull(file) ?: return BookPositionsData()
        return try {
            json.decodeFromString(BookPositionsData.serializer(), text)
        } catch (e: Exception) {
            file.renameTo(File(file.parentFile, "book_positions.broken-${System.currentTimeMillis()}.json"))
            BookPositionsData()
        }
    }

    fun save(data: BookPositionsData) {
        AtomicFile.writeText(file, encode(data))
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun encode(data: BookPositionsData): String = json.encodeToString(BookPositionsData.serializer(), data)
    }
}

/**
 * The app-wide book positions, saved in /sdcard/Listen/.state/book_positions.json (so an
 * uninstall can't lose them and the PC script backs them up). Works like the playlists: read
 * when a screen shows and again only if something else changed the file; nothing is saved
 * before the file has been read.
 */
object BookPositions {
    private val _positions = MutableStateFlow<Map<String, BookPosition>>(emptyMap())
    val positions: StateFlow<Map<String, BookPosition>> = _positions.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val file by lazy { BookPositionsFile(File(ListenPaths.state, "book_positions.json")) }
    private var knownModified = -1L

    fun load() {
        scope.launch {
            val modified = file.lastModified
            if (_loaded.value && modified == knownModified) return@launch
            val data = file.load()
            knownModified = file.lastModified
            _positions.value = data.books
            _loaded.value = true
        }
    }

    /**
     * Saves where the player is in book [id] (every 10 s while playing, and on pause, seek,
     * chapter change and background). Nothing is written when the place didn't change.
     */
    fun record(id: String, position: BookPosition) {
        if (!_loaded.value || _positions.value[id] == position) return
        _positions.update { all -> all + (id to position) }
        scope.launch { write() }
    }

    /** Marks the book finished; its place is kept. */
    fun markFinished(id: String) = change(id) { it.copy(finished = true) }

    /** Back to the start of the book, not finished (speed and last played are kept). */
    fun startOver(id: String) = change(id) { it.copy(fileIndex = 0, positionMs = 0, finished = false) }

    private fun change(id: String, update: (BookPosition) -> BookPosition) {
        if (!_loaded.value) return
        _positions.update { all -> all + (id to update(all[id] ?: BookPosition())) }
        scope.launch { write() }
    }

    private var lastWritten: Map<String, BookPosition>? = null

    private fun write() {
        val books = _positions.value
        if (books == lastWritten) return
        try {
            file.save(BookPositionsData(books = books))
            lastWritten = books
            knownModified = file.lastModified
        } catch (e: Exception) {
            Log.w("Listen", "Couldn't save book_positions.json", e)
        }
    }
}
