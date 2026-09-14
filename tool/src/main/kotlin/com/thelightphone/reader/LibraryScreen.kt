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
import androidx.lifecycle.viewModelScope
import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.BookRepository
import com.thelightphone.reader.data.ReaderDatabase
import com.thelightphone.reader.data.ReadingPosition
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One row on the library screen: a book plus how far through it Jordan is (CLAUDE.md 8). */
data class LibraryRow(
    val meta: BookMeta,
    val progressText: String,
)

sealed interface LibraryScreenState {
    data object Loading : LibraryScreenState
    data class Loaded(val rows: List<LibraryRow>) : LibraryScreenState
}

class LibraryScreenViewModel(
    private val bookRepository: BookRepository,
    private val readingPositionRepository: ReadingPositionRepository,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow<LibraryScreenState>(LibraryScreenState.Loading)
    val state: StateFlow<LibraryScreenState> = _state.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val positions = readingPositionRepository.getAll().associateBy { it.bookSlug }
            val rows = bookRepository.listBooks()
                .sortedBy { it.title.lowercase() }
                .map { meta -> LibraryRow(meta, progressText(meta, positions[meta.slug])) }
            _state.value = LibraryScreenState.Loaded(rows)
        }
    }

    private fun progressText(meta: BookMeta, position: ReadingPosition?): String {
        if (position == null) return "Not started"
        val totalChars = meta.chapters.sumOf { it.chars }
        if (totalChars <= 0) return "Not started"
        val readChars = meta.chapters
            .filter { it.index < position.chapterIndex }
            .sumOf { it.chars } + position.charOffset
        val percent = (readChars * 100 / totalChars).coerceIn(0, 100)
        return "$percent% read"
    }
}

@InitialScreen
class LibraryScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, LibraryScreenViewModel>(sealedActivity) {

    private val bookRepository = BookRepository(lightContext.fileShare)

    private val readingPositionRepository = ReadingPositionRepository.getInstance {
        lightContext.buildDatabase(ReaderDatabase::class.java, ReadingPositionRepository.DATABASE_NAME)
    }

    override val viewModelClass: Class<LibraryScreenViewModel>
        get() = LibraryScreenViewModel::class.java

    override fun createViewModel() = LibraryScreenViewModel(bookRepository, readingPositionRepository)

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

                    is LibraryScreenState.Loaded -> if (current.rows.isEmpty()) {
                        EmptyMessage("No books on this device yet.")
                    } else {
                        BookList(
                            rows = current.rows,
                            onSelect = { row ->
                                navigateTo(screenFactory = { ContentsScreen(it, row.meta) })
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyMessage(message: String) {
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
private fun BookList(rows: List<LibraryRow>, onSelect: (LibraryRow) -> Unit) {
    LightScrollView(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        rows.forEachIndexed { index, row ->
            BookRowView(
                row = row,
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable { onSelect(row) }
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
        )
        LightText(
            text = row.meta.author,
            variant = LightTextVariant.Detail,
            lighten = true,
        )
        LightText(
            text = row.progressText,
            variant = LightTextVariant.Detail,
            lighten = true,
        )
    }
}
