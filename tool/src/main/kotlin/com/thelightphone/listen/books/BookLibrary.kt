package com.thelightphone.listen.books

import android.util.Log
import com.thelightphone.listen.music.TagReader
import com.thelightphone.listen.music.sortKey
import com.thelightphone.listen.storage.AtomicFile
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The saved result of the last audiobook scan, so the library shows at once on launch.
 * It lives in the app's own files: it can always be rebuilt from the books on the phone.
 */
@Serializable
data class BookIndex(
    val version: Int = VERSION,
    val stamp: String = "",
    val books: List<Book> = emptyList(),
) {
    companion object {
        /** Bump when [Book] changes meaning, so old caches are thrown away and rebuilt. */
        const val VERSION = 1
    }
}

/** Reads and writes [BookIndex] as JSON in [file]. Missing, broken or old reads as empty. */
class BookIndexStore(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true }

    fun load(): BookIndex {
        val text = AtomicFile.readTextOrNull(file) ?: return BookIndex()
        val index = try {
            json.decodeFromString(BookIndex.serializer(), text)
        } catch (e: Exception) {
            return BookIndex()
        }
        return if (index.version == BookIndex.VERSION) index else BookIndex()
    }

    fun save(index: BookIndex) {
        AtomicFile.writeText(file, json.encodeToString(BookIndex.serializer(), index))
    }
}

/**
 * What the audiobook screens show. [books] are A–Z by title, one per id. [loaded] turns true
 * once the saved index has been read; [updating] is true while changed books are read.
 */
data class BookLibraryState(
    val books: List<Book> = emptyList(),
    val loaded: Boolean = false,
    val updating: Boolean = false,
) {
    private val byId: Map<String, Book> by lazy { books.associateBy { it.id } }

    fun book(id: String): Book? = byId[id]
}

/**
 * The app-wide audiobook library. Screens call [refresh] every time they come to the front.
 * All work happens in the background: the saved index shows first, then, only if the
 * audiobooks folder changed, changed books are read again. The UI thread never waits.
 */
object BookLibrary {
    private val _state = MutableStateFlow(BookLibraryState())
    val state: StateFlow<BookLibraryState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<File>(Channel.CONFLATED)
    private var index: BookIndex? = null

    init {
        scope.launch {
            for (indexDir in requests) {
                try {
                    refreshNow(indexDir)
                } catch (e: Exception) {
                    Log.w("Listen", "Audiobook refresh failed", e)
                    _state.update { it.copy(loaded = true, updating = false) }
                }
            }
        }
    }

    /** Checks for changed books in the background. [filesDir] holds the index cache. */
    fun refresh(filesDir: File) {
        if (StorageAccess.hasAllFilesAccess()) requests.trySend(File(filesDir, "cache"))
    }

    private fun refreshNow(indexDir: File) {
        val store = BookIndexStore(File(indexDir, "book_index.json"))
        val known = index ?: store.load().also { saved ->
            index = saved
            publish(saved.books)
        }

        val scanner = BookScanner(ListenPaths.audiobooks, ListenPaths.lastSync, TagReader::read)
        val stamp = scanner.stamp()
        if (stamp == known.stamp) return

        _state.update { it.copy(updating = true) }
        try {
            val books = scanner.scan(known.books, onProgress = ::publish)
            val updated = BookIndex(stamp = stamp, books = books)
            index = updated
            publish(books)
            store.save(updated)
        } finally {
            _state.update { it.copy(updating = false) }
        }
    }

    private fun publish(books: List<Book>) {
        val shown = uniqueById(books).sortedWith(compareBy({ sortKey(it.title) }, { it.folder }))
        _state.update { it.copy(books = shown, loaded = true) }
    }
}

/** One book per id (the first found), so a book sent twice shows once. */
fun uniqueById(books: List<Book>): List<Book> = books.distinctBy { it.id }
