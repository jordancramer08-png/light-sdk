package com.thelightphone.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.reader.comics.cleanComicTitle
import com.thelightphone.reader.comics.parentPath
import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.ComicPositionRepository
import com.thelightphone.reader.data.ComicStore
import com.thelightphone.reader.data.CoverSize
import com.thelightphone.reader.data.LibraryFilterPreference
import com.thelightphone.reader.data.LibraryGroupSeriesPreference
import com.thelightphone.reader.data.LibrarySectionPreference
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
import com.thelightphone.sdk.ui.lightClickable
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
    private val sectionPreference: LibrarySectionPreference,
    private val comicStore: ComicStore,
    comicPositionRepository: ComicPositionRepository,
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

    /**
     * All books (or comics), or one reading list. Starts on all of them each time the app opens,
     * and again when the section changes.
     */
    var view: LibraryView = LibraryView.AllBooks
        private set

    /** Books or Comics: the last one shown, remembered between launches. Null until read. */
    private val _section = MutableStateFlow<LibrarySection?>(null)
    val section: StateFlow<LibrarySection?> = _section.asStateFlow()

    /** The Comics section's rows: the top of the comics folder, or one list's comics. */
    val comics = ComicFolderLoader(comicStore, comicPositionRepository, readingStatusRepository, viewModelScope)

    /** The reading list shown in the Comics section, or null for all comics. */
    private val _comicsList = MutableStateFlow<ReadingList?>(null)
    val comicsList: StateFlow<ReadingList?> = _comicsList.asStateFlow()

    init {
        // The Library opens first, so the saved theme is read here, once per launch.
        viewModelScope.launch { ReaderThemeController.loadOnce(themePreference) }
        // Cached covers of comics taken off the phone since last time are cleared away.
        viewModelScope.launch(Dispatchers.IO) { comicStore.removeOrphans() }
    }

    /** Runs every time the library comes to the front, so new books and new progress show up. */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
        viewModelScope.launch {
            if (_section.value == null) _section.value = sectionPreference.load()
            if (_section.value == LibrarySection.COMICS) showComics()
        }
    }

    /** Comics still being read wait until the Library is back in front. */
    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        comics.stop()
    }

    /** Switches between Books and Comics (the bar at the bottom), and remembers it. */
    fun changeSection(newSection: LibrarySection) {
        if (newSection == _section.value) return
        _section.value = newSection
        view = LibraryView.AllBooks
        viewModelScope.launch { sectionPreference.save(newSection) }
        if (newSection == LibrarySection.COMICS) {
            showComics()
        } else {
            comics.stop()
            showAgain()
        }
    }

    /** All comics (the top of the comics folder), or the comics in the chosen list. */
    private fun showComics() {
        val shown = view
        if (shown !is LibraryView.OneList) {
            _comicsList.value = null
            comics.loadFolder("")
            return
        }
        viewModelScope.launch {
            val (list, slugs) = DatabaseQueue.read {
                readingListRepository.list(shown.listId) to readingListRepository.bookSlugs(shown.listId)
            }
            if (view != shown) return@launch // a different choice was made while this one loaded
            if (list == null) {
                // The list was deleted: fall back to all comics.
                view = LibraryView.AllBooks
                showComics()
                return@launch
            }
            _comicsList.value = list
            comics.loadList(slugs)
        }
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

    /** Shows all books (or comics) or one list (picked on the Lists screen). */
    fun changeView(newView: LibraryView) {
        view = newView
        if (_section.value == LibrarySection.COMICS) showComics() else showAgain()
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

    private val comicStore = ComicStore(lightContext.filesDir)

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
        LibrarySectionPreference(lightContext.dataStore),
        comicStore,
        ComicPositionRepository.getInstance { lightContext.readerDatabase() },
    )

    @Composable
    override fun Content() {
        val section by viewModel.section.collectAsState()

        ThemedScreen {
            when (section) {
                null -> Unit // the remembered section is being read (a blink)
                LibrarySection.BOOKS -> BooksSection(modifier = Modifier.weight(1f))
                LibrarySection.COMICS -> ComicsSection(modifier = Modifier.weight(1f))
            }
            section?.let { SectionBar(current = it, onSelect = viewModel::changeSection) }
        }
    }

    @Composable
    private fun BooksSection(modifier: Modifier) {
        val state by viewModel.state.collectAsState()
        val shownList = (state as? LibraryScreenState.Loaded)?.list
        LightTopBar(
            leftButton = LightBarButton.LightIcon(icon = LightIcons.LARGE_LIST, onClick = ::openLists),
            center = LightTopBarCenter.Text(shownList?.name ?: "Books"),
            rightButton = rightButton(state),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        when (val current = state) {
            is LibraryScreenState.Loading -> Box(modifier = modifier)
            is LibraryScreenState.Preparing -> CenteredMessage(preparingText(current.remaining), modifier)
            is LibraryScreenState.Loaded ->
                if (current.entries.isNotEmpty()) {
                    Box(modifier = modifier) {
                        LibraryEntryList(
                            entries = current.entries,
                            onSelectBook = ::openBook,
                            onSelectSeries = ::openSeries,
                            continueReading = current.continueReading,
                            coverFile = if (current.showCovers) ::smallCoverFile else null,
                        )
                    }
                } else if (current.list != null) {
                    CenteredMessage("No books in this list yet.\n\nAdd one from a book's Contents.", modifier)
                } else {
                    CenteredMessage(emptyFilterText(current.filter), modifier)
                }
        }
    }

    /** The top of the comics folder (with Continue reading), or one list's comics. */
    @Composable
    private fun ComicsSection(modifier: Modifier) {
        val state by viewModel.comics.state.collectAsState()
        val list by viewModel.comicsList.collectAsState()
        val shownList = list
        LightTopBar(
            leftButton = LightBarButton.LightIcon(icon = LightIcons.LARGE_LIST, onClick = ::openLists),
            center = LightTopBarCenter.Text(shownList?.name ?: "Comics"),
            rightButton = shownList?.let { LightBarButton.LightIcon(icon = LightIcons.PENCIL, onClick = { openListComics(it) }) },
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )
        val loaded = state
        when {
            loaded == null -> Box(modifier = modifier)
            loaded.entries.isNotEmpty() || loaded.continueReading != null ->
                ComicEntryList(
                    state = loaded,
                    coverFile = comicStore::coverFile,
                    onOpen = { openComicEntry(it, comicStore) },
                    modifier = modifier,
                )
            shownList != null -> CenteredMessage("No comics in this list yet.\n\nAdd one from a comic's screen.", modifier)
            else -> CenteredMessage("No comics on this device yet.", modifier)
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
        val allLabel = if (viewModel.section.value == LibrarySection.COMICS) "All comics" else "All books"
        navigateTo(
            screenFactory = { ListsScreen(it, viewModel.view, allLabel) },
            resultCallback = { chosen -> viewModel.changeView(chosen) },
        )
    }

    /** The Library refreshes when it comes back, so changes made there show up. */
    private fun openListBooks(list: ReadingList, entries: List<LibraryEntry>) {
        val books = entries.filterIsInstance<LibraryEntry.Book>().map { ListItem(it.row.meta.slug, it.row.meta.title, it.row.meta.author) }
        navigateTo(screenFactory = { ListBooksScreen(it, list.id, list.name, books) })
    }

    /** The list's comics, to rearrange; each shows its folder under its title. */
    private fun openListComics(list: ReadingList) {
        val comics = viewModel.comics.state.value?.entries.orEmpty().filterIsInstance<ComicEntry.Comic>().map {
            ListItem(it.slug, it.title, cleanComicTitle(parentPath(it.path).substringAfterLast('/')))
        }
        navigateTo(screenFactory = { ListBooksScreen(it, list.id, list.name, comics) })
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

/**
 * BOOKS and COMICS along the bottom of the Library. The one showing is underlined, so it
 * reads without color; tapping the other switches to it.
 */
@Composable
private fun SectionBar(current: LibrarySection, onSelect: (LibrarySection) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 1f.gridUnitsAsDp())
            .height(SECTION_BAR_GRID_UNITS.gridUnitsAsDp()),
    ) {
        for (section in LibrarySection.entries) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .lightClickable { onSelect(section) },
                contentAlignment = Alignment.Center,
            ) {
                LightText(text = section.name, variant = LightTextVariant.Button, underline = section == current)
            }
        }
    }
}

/** The bar's height, the same as the SDK's own bottom bar. */
private const val SECTION_BAR_GRID_UNITS = 4f

/** A lighter, centered message filling the space it's given (an empty list, "Preparing…"). */
@Composable
fun CenteredMessage(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
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
