package com.thelightphone.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.CoverSize
import com.thelightphone.reader.data.LibraryFilterPreference
import com.thelightphone.reader.data.LibraryGroupSeriesPreference
import com.thelightphone.reader.data.LibraryShowCoversPreference
import com.thelightphone.reader.data.LibrarySortPreference
import com.thelightphone.reader.data.LibraryStore
import com.thelightphone.reader.data.ReadingList
import com.thelightphone.reader.data.DatabaseQueue
import com.thelightphone.reader.data.ReadingListRepository
import com.thelightphone.reader.data.readerDatabase
import com.thelightphone.reader.data.ReaderThemePreference
import com.thelightphone.reader.data.ReadingPosition
import com.thelightphone.reader.data.ReadingPositionRepository
import com.thelightphone.reader.data.ReadingStatusRepository
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface LibraryScreenState {
    /** First moments after launch, before we know anything. Draws nothing. */
    data object Loading : LibraryScreenState

    /** New or changed EPUBs are being parsed; [remaining] counts down. */
    data class Preparing(val remaining: Int) : LibraryScreenState

    /**
     * [list] is the reading list being shown, or null for all books. [continueReading] is the
     * book last opened, shown as the first row (all books only, and only if the filter shows it).
     */
    data class Loaded(
        val entries: List<LibraryEntry>,
        val list: ReadingList? = null,
        val filter: LibraryFilter = LibraryFilter.DEFAULT,
        val continueReading: LibraryRow? = null,
        val showCovers: Boolean = true,
    ) : LibraryScreenState
}

class LibraryScreenViewModel(
    private val libraryStore: LibraryStore,
    private val readingPositionRepository: ReadingPositionRepository,
    private val readingListRepository: ReadingListRepository,
    private val readingStatusRepository: ReadingStatusRepository,
    private val sortPreference: LibrarySortPreference,
    private val filterPreference: LibraryFilterPreference,
    private val groupSeriesPreference: LibraryGroupSeriesPreference,
    private val showCoversPreference: LibraryShowCoversPreference,
    themePreference: ReaderThemePreference,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow<LibraryScreenState>(LibraryScreenState.Loading)
    val state: StateFlow<LibraryScreenState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    // The sort and filter choices and the last-loaded books are only touched on the main
    // thread, so changing them while a refresh is running can't mix up the list.
    private var choicesLoaded = false
    var choices = SortAndFilter(LibrarySort.DEFAULT, LibraryFilter.DEFAULT)
        private set
    private var books = emptyList<BookMeta>()
    private var positions = emptyMap<String, ReadingPosition>()
    private var statuses = emptyMap<String, ReadingStatus>()
    private var booksLoaded = false

    /** All books, or one reading list. Starts on all books each time the app opens. */
    var view: LibraryView = LibraryView.AllBooks
        private set

    init {
        // The Library opens first, so the saved theme is read here, once per launch.
        viewModelScope.launch { ReaderThemeController.loadOnce(themePreference) }
    }

    /** Runs every time the library comes to the front, so new books and new progress show up. */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

    private fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            if (!choicesLoaded) {
                choices = SortAndFilter(
                    sortPreference.load(),
                    filterPreference.load(),
                    groupSeriesPreference.load(),
                    showCoversPreference.load(),
                )
                choicesLoaded = true
            }
            books = withContext(Dispatchers.IO) {
                libraryStore.refresh { remaining ->
                    _state.value = LibraryScreenState.Preparing(remaining)
                }
            }
            positions = withContext(Dispatchers.IO) {
                readingPositionRepository.getAll().associateBy { it.bookSlug }
            }
            statuses = DatabaseQueue.read { readingStatusRepository.getAll() }
            booksLoaded = true
            showCurrentView()
        }
    }

    /** Re-orders, filters and groups the list right away, and saves the choices for next time. */
    fun changeSortAndFilter(newChoices: SortAndFilter) {
        choices = newChoices
        choicesLoaded = true
        showAgain()
        viewModelScope.launch {
            sortPreference.save(newChoices.sort)
            filterPreference.save(newChoices.filter)
            groupSeriesPreference.save(newChoices.groupSeries)
            showCoversPreference.save(newChoices.showCovers)
        }
    }

    /** Shows all books or one list (picked on the Lists screen). */
    fun changeView(newView: LibraryView) {
        view = newView
        showAgain()
    }

    /** Redraws with the books already loaded. Before they are, the running refresh will draw. */
    private fun showAgain() {
        if (booksLoaded) viewModelScope.launch { showCurrentView() }
    }

    private suspend fun showCurrentView() {
        val shown = view
        if (shown !is LibraryView.OneList) {
            val entries = libraryEntries(books, positions, choices.sort, statuses, choices.filter, choices.groupSeries)
            _state.value = LibraryScreenState.Loaded(
                entries,
                filter = choices.filter,
                continueReading = continueReadingRow(books, positions, statuses, choices.filter),
                showCovers = choices.showCovers,
            )
            return
        }
        val (list, slugs) = DatabaseQueue.read {
            readingListRepository.list(shown.listId) to readingListRepository.bookSlugs(shown.listId)
        }
        if (view != shown) return // a different choice was made while this one loaded
        if (list == null) {
            // The list was deleted: fall back to all books.
            view = LibraryView.AllBooks
            showCurrentView()
            return
        }
        // A list always shows its books one by one, never grouped into series.
        val entries = listRows(books, positions, slugs, statuses).map { LibraryEntry.Book(it) }
        _state.value = LibraryScreenState.Loaded(entries, list, showCovers = choices.showCovers)
    }
}

@InitialScreen
class LibraryScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, LibraryScreenViewModel>(sealedActivity) {

    private val libraryStore = LibraryStore(lightContext.filesDir)

    private val readingPositionRepository = ReadingPositionRepository.getInstance { lightContext.readerDatabase() }

    override val viewModelClass: Class<LibraryScreenViewModel>
        get() = LibraryScreenViewModel::class.java

    override fun createViewModel() = LibraryScreenViewModel(
        libraryStore,
        readingPositionRepository,
        ReadingListRepository.getInstance { lightContext.readerDatabase() },
        ReadingStatusRepository.getInstance { lightContext.readerDatabase() },
        LibrarySortPreference(lightContext.dataStore),
        LibraryFilterPreference(lightContext.dataStore),
        LibraryGroupSeriesPreference(lightContext.dataStore),
        LibraryShowCoversPreference(lightContext.dataStore),
        ReaderThemePreference(lightContext.dataStore),
    )

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()

        ThemedScreen {
            val shownList = (state as? LibraryScreenState.Loaded)?.list
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.LARGE_LIST, onClick = ::openLists),
                center = LightTopBarCenter.Text(shownList?.name ?: "Library"),
                rightButton = rightButton(state),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            when (val current = state) {
                is LibraryScreenState.Loading -> Unit
                is LibraryScreenState.Preparing -> CenteredMessage(preparingText(current.remaining))
                is LibraryScreenState.Loaded ->
                    if (current.entries.isNotEmpty()) {
                        LibraryEntryList(
                            entries = current.entries,
                            onSelectBook = ::openBook,
                            onSelectSeries = ::openSeries,
                            continueReading = current.continueReading,
                            coverFile = if (current.showCovers) ::smallCoverFile else null,
                        )
                    } else if (current.list != null) {
                        CenteredMessage("No books in this list yet.\n\nAdd one from a book's Contents.")
                    } else {
                        CenteredMessage(emptyFilterText(current.filter))
                    }
            }
        }
    }

    /**
     * All books: the Sort & Filter button. One list: a pencil to rearrange it (a list keeps its
     * own order and shows all its books, so sorting and filtering don't apply).
     */
    private fun rightButton(state: LibraryScreenState): LightBarButton {
        val loaded = state as? LibraryScreenState.Loaded
        val list = loaded?.list
        return if (list != null) {
            LightBarButton.LightIcon(icon = LightIcons.PENCIL, onClick = { openListBooks(list, loaded.entries) })
        } else {
            LightBarButton.LightIcon(icon = LightIcons.REVERSE_ORDER, onClick = ::openSortAndFilter)
        }
    }

    private fun openLists() {
        navigateTo(
            screenFactory = { ListsScreen(it, viewModel.view) },
            resultCallback = { chosen -> viewModel.changeView(chosen) },
        )
    }

    /** The Library refreshes when it comes back, so changes made there show up. */
    private fun openListBooks(list: ReadingList, entries: List<LibraryEntry>) {
        val books = entries.filterIsInstance<LibraryEntry.Book>().map { it.row.meta }
        navigateTo(screenFactory = { ListBooksScreen(it, list.id, list.name, books) })
    }

    private fun openSortAndFilter() {
        navigateTo(
            screenFactory = { SortFilterScreen(it, viewModel.choices) },
            resultCallback = { chosen -> viewModel.changeSortAndFilter(chosen) },
        )
    }

    private fun openBook(meta: BookMeta) {
        navigateTo(screenFactory = { ReaderScreen(it, meta, libraryStore) })
    }

    private fun openSeries(series: LibraryEntry.Series) {
        val books = series.rows.map { it.meta }
        val showCovers = viewModel.choices.showCovers
        navigateTo(screenFactory = { SeriesScreen(it, series.name, books, libraryStore, showCovers) })
    }

    private fun smallCoverFile(book: BookMeta) = libraryStore.coverFile(book, CoverSize.SMALL)
}

private fun preparingText(remaining: Int): String =
    if (remaining == 1) "Preparing 1 book…" else "Preparing $remaining books…"

@Composable
private fun CenteredMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = message,
            variant = LightTextVariant.Copy,
            lighten = true,
            align = TextAlign.Center,
        )
    }
}
