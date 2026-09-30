package com.thelightphone.listen.books

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.music.ListSort
import com.thelightphone.listen.music.ListTopBar
import com.thelightphone.listen.music.MusicLazyList
import com.thelightphone.listen.music.fieldOr
import com.thelightphone.listen.storage.Settings
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.NowPlayingBar
import com.thelightphone.listen.ui.SortField
import com.thelightphone.listen.ui.SortScreen
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.UpdatingLine
import com.thelightphone.sdk.SealedLightActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The library as shown: [continuing] books at the top, then the grouped, sorted [entries]. */
private data class LibraryView(val tag: ListSort, val continuing: List<Book>, val entries: List<BookEntry>)

/**
 * Audiobooks: "Continue listening" (books in progress, most recent first), then the library,
 * with each series as one row (opening its books in number order). Sorted by title, author
 * or recently played, A–Z or Z–A (remembered).
 */
class BooksScreen(sealedActivity: SealedLightActivity) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val library by BookLibrary.state.collectAsState()
        val positions by BookPositions.positions.collectAsState()
        val positionsLoaded by BookPositions.loaded.collectAsState()
        val settings by Settings.settings.collectAsState()
        val settingsLoaded by Settings.loaded.collectAsState()
        val sort = settings.bookSort.let { it.copy(field = it.fieldOr(BookSortField.AUTHOR).name) }
        // Grouped and sorted off the main thread.
        val view by produceState<LibraryView?>(null, library.books, positions, sort) {
            value = withContext(Dispatchers.Default) {
                LibraryView(
                    tag = sort,
                    continuing = continueListening(library.books, positions),
                    entries = libraryEntries(library.books, positions, sort.fieldOr(BookSortField.AUTHOR), sort.descending),
                )
            }
        }
        ThemedScreen {
            ListTopBar(title = "Audiobooks", onBack = { goBack() }, onSort = { openSort(sort) })
            val listArea = Modifier.weight(1f)
            val shown = view
            when {
                !library.loaded || !settingsLoaded || !positionsLoaded || shown == null -> Box(modifier = listArea)
                shown.entries.isNotEmpty() -> Box(modifier = listArea) {
                    MusicLazyList(tag = shown.tag, rowGridUnits = BOOK_ROW_GRID_UNITS) {
                        continueRows(shown.continuing, positions, moreFollow = true, onOpen = { openBook(it.id) })
                        entryRows(shown.entries, onOpenBook = { openBook(it.id) }, onOpenSeries = ::openSeries)
                    }
                }
                library.updating -> CenteredMessage("Reading your audiobooks…", modifier = listArea)
                else -> CenteredMessage(NO_BOOKS_MESSAGE, modifier = listArea)
            }
            UpdatingLine(visible = library.updating)
            NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }

    private fun openSeries(series: BookEntry.Series) {
        navigateTo(screenFactory = { BookSeriesScreen(it, series.key, series.name) })
    }

    private fun openSort(current: ListSort) {
        navigateTo(
            screenFactory = { SortScreen(it, "Sort audiobooks", FIELDS, current) },
            resultCallback = { chosen -> Settings.change { it.copy(bookSort = chosen) } },
        )
    }

    private companion object {
        val FIELDS = BookSortField.entries.map {
            SortField(it.name, it.label, directions = if (it == BookSortField.RECENT) "newest first" to "oldest first" else null)
        }
    }
}

/** One series' books in number order; each opens its book screen. */
class BookSeriesScreen(
    sealedActivity: SealedLightActivity,
    private val seriesKey: String,
    private val seriesName: String,
) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val library by BookLibrary.state.collectAsState()
        val positions by BookPositions.positions.collectAsState()
        val books by produceState<List<Book>?>(null, library.books) {
            value = withContext(Dispatchers.Default) { seriesBooks(library.books, seriesKey) }
        }
        ThemedScreen {
            ListTopBar(title = seriesName, onBack = { goBack() })
            val listArea = Modifier.weight(1f)
            val shown = books
            when {
                !library.loaded || shown == null -> Box(modifier = listArea)
                shown.isEmpty() -> CenteredMessage("These books aren't on the phone any more.", modifier = listArea)
                else -> Box(modifier = listArea) {
                    MusicLazyList(tag = seriesKey, rowGridUnits = BOOK_ROW_GRID_UNITS) {
                        seriesBookRows(shown, positions, onOpen = { openBook(it.id) })
                    }
                }
            }
            UpdatingLine(visible = library.updating)
            NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }
}

const val NO_BOOKS_MESSAGE = "No audiobooks on the phone yet.\n\nSend some with Listen-Phone-Sync.cmd on the PC."
