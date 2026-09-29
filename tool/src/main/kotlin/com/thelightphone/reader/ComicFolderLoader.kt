package com.thelightphone.reader

import com.thelightphone.reader.comics.ComicItemKind
import com.thelightphone.reader.comics.comicPathOf
import com.thelightphone.reader.comics.folderSummaryText
import com.thelightphone.reader.data.ComicMeta
import com.thelightphone.reader.data.ComicPositionRepository
import com.thelightphone.reader.data.ComicStore
import com.thelightphone.reader.data.DatabaseQueue
import com.thelightphone.reader.data.ReadingStatusRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A comics folder (or a list's comics) as shown. [continueReading] is the comic opened last (top folder only). */
data class ComicFolderState(
    val entries: List<ComicEntry>,
    val continueReading: ComicEntry.Comic? = null,
)

/**
 * Loads what one comics folder shows, for the Library's Comics section and ComicFolderScreen.
 * The rows show at once from the file names; comics never read before say "Preparing…" and
 * are then read one at a time, each row filling in (page count, cover) as it's done.
 */
class ComicFolderLoader(
    private val store: ComicStore,
    private val positionRepository: ComicPositionRepository,
    private val statusRepository: ReadingStatusRepository,
    private val scope: CoroutineScope,
) {
    /** Null until the first load finishes. */
    private val _state = MutableStateFlow<ComicFolderState?>(null)
    val state: StateFlow<ComicFolderState?> = _state.asStateFlow()

    private var job: Job? = null

    /** Shows [folder] ("" = the top of the comics folder, which also gets the Continue reading row). */
    fun loadFolder(folder: String) {
        restart {
            val items = withContext(Dispatchers.IO) { store.list(folder) }
            val comicPaths = items.filter { it.kind == ComicItemKind.COMIC }.map { it.path }
            val metas = withContext(Dispatchers.IO) { cachedMetas(comicPaths) }
            val summaries = withContext(Dispatchers.IO) {
                items.filter { it.kind == ComicItemKind.FOLDER }.associate { it.path to summaryOf(it.path) }
            }
            val (positions, statuses) = DatabaseQueue.read { positionRepository.getAll() to statusRepository.getAll() }
            val continueReading = if (folder.isEmpty()) {
                withContext(Dispatchers.IO) {
                    lastOpenedComicPath(positions.values, store::exists)?.let { path ->
                        comicEntry(path, store.prepared(path), positions, statuses)
                    }
                }
            } else {
                null
            }
            fun show() = ComicFolderState(
                comicEntries(items, metas, positions, statuses) { summaries[it].orEmpty() },
                continueReading,
            )
            _state.value = show()
            prepareMissing(comicPaths, metas) { _state.value = show() }
        }
    }

    /** Shows the comics in one reading list, in the list's own order. Comics no longer on the phone are skipped. */
    fun loadList(slugs: List<String>) {
        restart {
            val paths = slugs.mapNotNull(::comicPathOf)
            val existing = withContext(Dispatchers.IO) { paths.filter(store::exists) }
            val metas = withContext(Dispatchers.IO) { cachedMetas(existing) }
            val (positions, statuses) = DatabaseQueue.read { positionRepository.getAll() to statusRepository.getAll() }
            fun show() = ComicFolderState(existing.map { comicEntry(it, metas[it], positions, statuses) })
            _state.value = show()
            prepareMissing(existing, metas) { _state.value = show() }
        }
    }

    /** Stops reading comics in the background (the screen went away; it loads again when it's back). */
    fun stop() {
        job?.cancel()
    }

    private fun restart(load: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { load() }
    }

    private fun cachedMetas(paths: List<String>): MutableMap<String, ComicMeta> {
        val metas = mutableMapOf<String, ComicMeta>()
        for (path in paths) store.cached(path)?.let { metas[path] = it }
        return metas
    }

    /** Reads each comic not cached yet, in the order shown, calling [onEach] after each one. */
    private suspend fun prepareMissing(paths: List<String>, metas: MutableMap<String, ComicMeta>, onEach: () -> Unit) {
        for (path in paths) {
            if (path in metas) continue
            val meta = withContext(Dispatchers.IO) { store.prepared(path) } ?: continue
            metas[path] = meta
            onEach()
        }
    }

    private fun summaryOf(path: String): String {
        val (folders, comics) = store.folderCounts(path)
        return folderSummaryText(folders, comics)
    }
}
