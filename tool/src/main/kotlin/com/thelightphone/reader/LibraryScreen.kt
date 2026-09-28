package com.thelightphone.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.LibrarySortPreference
import com.thelightphone.reader.data.LibraryStore
import com.thelightphone.reader.data.ReaderDatabase
import com.thelightphone.reader.data.ReaderThemePreference
import com.thelightphone.reader.data.ReadingPosition
import com.thelightphone.reader.data.ReadingPositionRepository
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
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

    data class Loaded(val rows: List<LibraryRow>) : LibraryScreenState
}

class LibraryScreenViewModel(
    private val libraryStore: LibraryStore,
    private val readingPositionRepository: ReadingPositionRepository,
    private val sortPreference: LibrarySortPreference,
    themePreference: ReaderThemePreference,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow<LibraryScreenState>(LibraryScreenState.Loading)
    val state: StateFlow<LibraryScreenState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    // The sort choice and the last-loaded books are only touched on the main thread, so
    // changing the sort while a refresh is running can't mix up the order.
    private var sortLoaded = false
    var sort = LibrarySort.DEFAULT
        private set
    private var books = emptyList<BookMeta>()
    private var positions = emptyMap<String, ReadingPosition>()

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
            if (!sortLoaded) {
                sort = sortPreference.load()
                sortLoaded = true
            }
            books = withContext(Dispatchers.IO) {
                libraryStore.refresh { remaining ->
                    _state.value = LibraryScreenState.Preparing(remaining)
                }
            }
            positions = withContext(Dispatchers.IO) {
                readingPositionRepository.getAll().associateBy { it.bookSlug }
            }
            showRows()
        }
    }

    /** Re-orders the list right away and saves the choice for next time. */
    fun changeSort(newSort: LibrarySort) {
        sort = newSort
        sortLoaded = true
        if (_state.value is LibraryScreenState.Loaded) showRows()
        viewModelScope.launch { sortPreference.save(newSort) }
    }

    private fun showRows() {
        _state.value = LibraryScreenState.Loaded(libraryRows(books, positions, sort))
    }
}

@InitialScreen
class LibraryScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, LibraryScreenViewModel>(sealedActivity) {

    private val libraryStore = LibraryStore(lightContext.filesDir)

    private val readingPositionRepository = ReadingPositionRepository.getInstance {
        lightContext.buildDatabase(ReaderDatabase::class.java, ReadingPositionRepository.DATABASE_NAME)
    }

    override val viewModelClass: Class<LibraryScreenViewModel>
        get() = LibraryScreenViewModel::class.java

    override fun createViewModel() = LibraryScreenViewModel(
        libraryStore,
        readingPositionRepository,
        LibrarySortPreference(lightContext.dataStore),
        ReaderThemePreference(lightContext.dataStore),
    )

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()

        ThemedScreen {
            LightTopBar(
                center = LightTopBarCenter.Text("Library"),
                rightButton = LightBarButton.LightIcon(icon = LightIcons.REVERSE_ORDER, onClick = ::openSort),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            when (val current = state) {
                is LibraryScreenState.Loading -> Unit
                is LibraryScreenState.Preparing -> CenteredMessage(preparingText(current.remaining))
                is LibraryScreenState.Loaded ->
                    if (current.rows.isEmpty()) {
                        CenteredMessage("No books on this device yet.")
                    } else {
                        BookList(rows = current.rows, onSelect = ::openBook)
                    }
            }
        }
    }

    private fun openSort() {
        navigateTo(
            screenFactory = { SortScreen(it, viewModel.sort) },
            resultCallback = { chosen -> viewModel.changeSort(chosen) },
        )
    }

    private fun openBook(meta: BookMeta) {
        navigateTo(screenFactory = { ReaderScreen(it, meta, libraryStore) })
    }
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

@Composable
private fun BookList(rows: List<LibraryRow>, onSelect: (BookMeta) -> Unit) {
    LightScrollView(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        rows.forEachIndexed { index, row ->
            // Books we can't open aren't tappable; the row says why.
            val tap = if (row.canOpen) Modifier.lightClickable { onSelect(row.meta) } else Modifier
            BookRowView(
                row = row,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(tap)
                    .padding(vertical = 0.75f.gridUnitsAsDp()),
            )
            if (index != rows.lastIndex) {
                HairlineDivider()
            }
        }
    }
}

@Composable
private fun BookRowView(row: LibraryRow, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        LightText(
            text = row.meta.title,
            variant = LightTextVariant.Copy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (row.canOpen) {
            LightText(
                text = row.meta.author,
                variant = LightTextVariant.Detail,
                lighten = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // "NN% read" in the accent color; "Not started" and problems stay lighter.
        LightText(
            text = row.statusText,
            variant = LightTextVariant.Detail,
            lighten = !row.isStarted,
            color = if (row.isStarted) LocalReaderAccent.current else null,
        )
    }
}
