package com.thelightphone.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.LibraryStore
import com.thelightphone.reader.data.ReaderDatabase
import com.thelightphone.reader.data.ReadingPositionRepository
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
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
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow<LibraryScreenState>(LibraryScreenState.Loading)
    val state: StateFlow<LibraryScreenState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    /** Runs every time the library comes to the front, so new books and new progress show up. */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

    private fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            val books = libraryStore.refresh { remaining ->
                _state.value = LibraryScreenState.Preparing(remaining)
            }
            val positions = readingPositionRepository.getAll().associateBy { it.bookSlug }
            _state.value = LibraryScreenState.Loaded(libraryRows(books, positions))
        }
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

    override fun createViewModel() = LibraryScreenViewModel(libraryStore, readingPositionRepository)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    center = LightTopBarCenter.Text("Library"),
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
        LightText(
            text = row.statusText,
            variant = LightTextVariant.Detail,
            lighten = true,
        )
    }
}
