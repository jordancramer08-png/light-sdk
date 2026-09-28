package com.thelightphone.reader

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.DatabaseQueue
import com.thelightphone.reader.data.LibraryStore
import com.thelightphone.reader.data.ReadingPositionRepository
import com.thelightphone.reader.data.ReadingStatusRepository
import com.thelightphone.reader.data.readerDatabase
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SeriesViewModel(
    private val books: List<BookMeta>,
    private val readingPositionRepository: ReadingPositionRepository,
    private val readingStatusRepository: ReadingStatusRepository,
) : LightViewModel<Unit>() {

    /** Null until progress and status have been read (a moment at most). */
    private val _entries = MutableStateFlow<List<LibraryEntry>?>(null)
    val entries: StateFlow<List<LibraryEntry>?> = _entries.asStateFlow()

    /** Runs every time the screen comes to the front, so progress made in a book shows up. */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch {
            val positions = withContext(Dispatchers.IO) {
                readingPositionRepository.getAll().associateBy { it.bookSlug }
            }
            val statuses = DatabaseQueue.read { readingStatusRepository.getAll() }
            _entries.value = books.map { LibraryEntry.Book(libraryRow(it, positions, statuses)) }
        }
    }
}

/**
 * One series' books, in number order (CLAUDE.md 9), opened from a series row in the
 * Library. Each row is the Library's book row and opens the book the same way.
 */
class SeriesScreen(
    sealedActivity: SealedLightActivity,
    private val seriesName: String,
    private val books: List<BookMeta>,
    private val libraryStore: LibraryStore,
) : LightScreen<Unit, SeriesViewModel>(sealedActivity) {

    override val viewModelClass: Class<SeriesViewModel>
        get() = SeriesViewModel::class.java

    override fun createViewModel() = SeriesViewModel(
        books,
        ReadingPositionRepository.getInstance { lightContext.readerDatabase() },
        ReadingStatusRepository.getInstance { lightContext.readerDatabase() },
    )

    @Composable
    override fun Content() {
        val entries by viewModel.entries.collectAsState()

        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(seriesName),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            entries?.let { LibraryEntryList(entries = it, onSelectBook = ::openBook) }
        }
    }

    private fun openBook(meta: BookMeta) {
        navigateTo(screenFactory = { ReaderScreen(it, meta, libraryStore) })
    }
}
