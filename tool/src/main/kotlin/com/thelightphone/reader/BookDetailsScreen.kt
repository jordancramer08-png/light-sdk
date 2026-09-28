package com.thelightphone.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.ReadingPositionRepository
import com.thelightphone.reader.data.readerDatabase
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookDetailsViewModel(
    private val bookMeta: BookMeta,
    private val readingPositionRepository: ReadingPositionRepository,
) : LightViewModel<Unit>() {

    /** Null until the saved place has been read (a moment at most). */
    private val _rows = MutableStateFlow<List<DetailRow>?>(null)
    val rows: StateFlow<List<DetailRow>?> = _rows.asStateFlow()

    init {
        viewModelScope.launch {
            val position = withContext(Dispatchers.IO) { readingPositionRepository.get(bookMeta.slug) }
            _rows.value = bookDetailRows(bookMeta, position)
        }
    }
}

/**
 * Facts about one book, reached from its Contents screen (CLAUDE.md 9): title and author
 * at the top, then series, length, reading time, progress and file details. Read only.
 */
class BookDetailsScreen(
    sealedActivity: SealedLightActivity,
    private val bookMeta: BookMeta,
) : LightScreen<Unit, BookDetailsViewModel>(sealedActivity) {

    override val viewModelClass: Class<BookDetailsViewModel>
        get() = BookDetailsViewModel::class.java

    override fun createViewModel() = BookDetailsViewModel(
        bookMeta,
        ReadingPositionRepository.getInstance { lightContext.readerDatabase() },
    )

    @Composable
    override fun Content() {
        val rows by viewModel.rows.collectAsState()

        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Details"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            rows?.let { DetailsList(bookMeta, it, modifier = Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun DetailsList(bookMeta: BookMeta, rows: List<DetailRow>, modifier: Modifier = Modifier) {
    LightScrollView(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        Column(modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp())) {
            LightText(text = bookMeta.title, variant = LightTextVariant.Subheading)
            LightText(text = bookMeta.author, variant = LightTextVariant.Copy, lighten = true)
        }
        HairlineDivider()
        rows.forEachIndexed { i, row ->
            DetailRowView(row)
            if (i != rows.lastIndex) HairlineDivider()
        }
    }
}

/** "Words          95,312": the label lighter on the left, the value on the right. Long values wrap. */
@Composable
private fun DetailRowView(row: DetailRow) {
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

/** The label column's share of the row width. */
private const val LABEL_WEIGHT = 0.4f
