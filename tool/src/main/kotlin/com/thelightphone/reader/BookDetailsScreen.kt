package com.thelightphone.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.CoverSize
import com.thelightphone.reader.data.DatabaseQueue
import com.thelightphone.reader.data.LibraryStore
import com.thelightphone.reader.data.ReadingPositionRepository
import com.thelightphone.reader.data.ReadingStatusRepository
import com.thelightphone.reader.data.readerDatabase
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class BookDetailsViewModel(
    private val bookMeta: BookMeta,
    private val coverFile: File,
    private val readingPositionRepository: ReadingPositionRepository,
    private val readingStatusRepository: ReadingStatusRepository,
) : LightViewModel<Unit>() {

    /** Null until the saved place has been read (a moment at most). */
    private val _rows = MutableStateFlow<List<DetailRow>?>(null)
    val rows: StateFlow<List<DetailRow>?> = _rows.asStateFlow()

    /** Null until the saved status has been read. */
    private val _status = MutableStateFlow<ReadingStatus?>(null)
    val status: StateFlow<ReadingStatus?> = _status.asStateFlow()

    /** The large cover, read before the rows show so the page doesn't jump; Missing if there is none. */
    var cover: CoverPicture = CoverPicture.Missing
        private set

    init {
        viewModelScope.launch {
            cover = CoverCache.load(coverFile, CoverCache.key(bookMeta, CoverSize.LARGE))
            val position = withContext(Dispatchers.IO) { readingPositionRepository.get(bookMeta.slug) }
            _rows.value = bookDetailRows(bookMeta, position)
        }
        viewModelScope.launch {
            _status.value = DatabaseQueue.read { readingStatusRepository.get(bookMeta.slug) }
        }
    }

    /** Shows the new status at once and saves it (the save finishes even if the screen closes). */
    fun changeStatus(newStatus: ReadingStatus) {
        _status.value = newStatus
        DatabaseQueue.write { readingStatusRepository.set(bookMeta.slug, newStatus) }
    }
}

/**
 * Facts about one book, reached from its Contents screen (CLAUDE.md 9): title and author
 * at the top, then its reading status (the one thing that can be changed here), then
 * series, length, reading time, progress and file details.
 */
class BookDetailsScreen(
    sealedActivity: SealedLightActivity,
    private val bookMeta: BookMeta,
) : LightScreen<Unit, BookDetailsViewModel>(sealedActivity) {

    override val viewModelClass: Class<BookDetailsViewModel>
        get() = BookDetailsViewModel::class.java

    override fun createViewModel() = BookDetailsViewModel(
        bookMeta,
        LibraryStore(lightContext.filesDir).coverFile(bookMeta, CoverSize.LARGE),
        ReadingPositionRepository.getInstance { lightContext.readerDatabase() },
        ReadingStatusRepository.getInstance { lightContext.readerDatabase() },
    )

    @Composable
    override fun Content() {
        val rows by viewModel.rows.collectAsState()
        val status by viewModel.status.collectAsState()

        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Details"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            val shownRows = rows
            val shownStatus = status
            if (shownRows != null && shownStatus != null) {
                DetailsList(
                    bookMeta = bookMeta,
                    cover = viewModel.cover,
                    status = shownStatus,
                    onStatusChange = viewModel::changeStatus,
                    rows = shownRows,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DetailsList(
    bookMeta: BookMeta,
    cover: CoverPicture,
    status: ReadingStatus,
    onStatusChange: (ReadingStatus) -> Unit,
    rows: List<DetailRow>,
    modifier: Modifier = Modifier,
) {
    LightScrollView(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        if (cover is CoverPicture.Found) {
            Image(
                bitmap = cover.image,
                contentDescription = null, // the title is written just below
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(COVER_HEIGHT_GRID_UNITS.gridUnitsAsDp())
                    .padding(bottom = 1f.gridUnitsAsDp()),
            )
        }
        Column(modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp())) {
            LightText(text = bookMeta.title, variant = LightTextVariant.Subheading)
            LightText(text = bookMeta.author, variant = LightTextVariant.Copy, lighten = true)
        }
        HairlineDivider()
        StatusChoices(current = status, onSelect = onStatusChange)
        HairlineDivider()
        rows.forEachIndexed { i, row ->
            DetailRowView(row)
            if (i != rows.lastIndex) HairlineDivider()
        }
    }
}

/**
 * "Status" then Want to Read, Reading, Finished: the current one has a filled circle, the
 * others an empty one, so it reads without color. Tapping one sets it.
 */
@Composable
fun StatusChoices(current: ReadingStatus, onSelect: (ReadingStatus) -> Unit) {
    Column(modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp())) {
        LightText(text = "Status", variant = LightTextVariant.Copy, lighten = true)
        ReadingStatus.entries.forEach { status ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable { onSelect(status) }
                    .padding(vertical = 0.5f.gridUnitsAsDp()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightText(text = status.label, variant = LightTextVariant.Copy, modifier = Modifier.weight(1f))
                LightIcon(icon = if (status == current) LightIcons.SELECT_ON else LightIcons.SELECT_OFF)
            }
        }
    }
}

/** "Words          95,312": the label lighter on the left, the value on the right. Long values wrap. */
@Composable
fun DetailRowView(row: DetailRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(
            text = row.label,
            variant = LightTextVariant.Copy,
            lighten = true,
            modifier = Modifier.weight(LABEL_WEIGHT).padding(end = 1f.gridUnitsAsDp()),
        )
        LightText(
            text = row.value,
            variant = LightTextVariant.Copy,
            align = TextAlign.End,
            modifier = Modifier.weight(1f - LABEL_WEIGHT),
        )
    }
}

/** The cover's height at the top of the page (its width follows its shape). */
private const val COVER_HEIGHT_GRID_UNITS = 14f

/** The label column's share of the row width. */
private const val LABEL_WEIGHT = 0.4f
