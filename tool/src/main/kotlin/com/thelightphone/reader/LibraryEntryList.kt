package com.thelightphone.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.reader.data.BookMeta
import java.io.File
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * Each row is this tall (grid units), divider included: three lines of one line each
 * (about 5.3 units together), so every row is the same height — the lazy list needs that.
 */
private const val ENTRY_ROW_GRID_UNITS = 7f

/** The cover at the left of a row: 2:3, a little taller than the row's three lines. */
private const val COVER_WIDTH_GRID_UNITS = 3.6f
private const val COVER_HEIGHT_GRID_UNITS = 5.4f
private const val COVER_GAP_GRID_UNITS = 0.75f

/** How strongly the Continue reading row is tinted with the accent. The label says what it is. */
private const val CONTINUE_TINT_ALPHA = 0.12f

/**
 * The Library's list, also used by the Series screen. Only the rows on screen are drawn,
 * so a long library scrolls smoothly. A book row opens the book; a series row opens the
 * series. [continueReading], when given, is a highlighted first row that opens that book.
 * [coverFile] gives each book's small cover file; null means covers are off.
 */
@Composable
fun LibraryEntryList(
    entries: List<LibraryEntry>,
    onSelectBook: (BookMeta) -> Unit,
    onSelectSeries: (LibraryEntry.Series) -> Unit = {},
    continueReading: LibraryRow? = null,
    coverFile: ((BookMeta) -> File)? = null,
) {
    LightLazyScrollView(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        uniformItemHeightGridUnits = ENTRY_ROW_GRID_UNITS,
    ) {
        if (continueReading != null) {
            item(key = "continue:" + continueReading.meta.slug) {
                UniformRow(
                    heightGridUnits = ENTRY_ROW_GRID_UNITS,
                    showDivider = entries.isNotEmpty(),
                    modifier = Modifier
                        .background(LocalReaderAccent.current.copy(alpha = CONTINUE_TINT_ALPHA))
                        .lightClickable { onSelectBook(continueReading.meta) },
                ) {
                    WithCover(continueReading.meta, coverFile, modifier = Modifier.padding(horizontal = 0.5f.gridUnitsAsDp())) {
                        ContinueRowView(continueReading)
                    }
                }
            }
        }
        itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
            val tap = when (entry) {
                is LibraryEntry.Series -> Modifier.lightClickable { onSelectSeries(entry) }
                // Books we can't open aren't tappable; the row says why.
                is LibraryEntry.Book ->
                    if (entry.row.canOpen) Modifier.lightClickable { onSelectBook(entry.row.meta) } else Modifier
            }
            UniformRow(
                heightGridUnits = ENTRY_ROW_GRID_UNITS,
                showDivider = index != entries.lastIndex,
                modifier = tap,
            ) {
                WithCover(entry.coverBook, coverFile) {
                    when (entry) {
                        is LibraryEntry.Book -> BookRowView(row = entry.row, modifier = Modifier.fillMaxWidth())
                        is LibraryEntry.Series -> SeriesRowView(series = entry, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** The row's text, with [book]'s cover at its left when covers are on ([coverFile] not null). */
@Composable
private fun WithCover(
    book: BookMeta,
    coverFile: ((BookMeta) -> File)?,
    modifier: Modifier = Modifier,
    text: @Composable () -> Unit,
) {
    if (coverFile == null) {
        Column(modifier = modifier.fillMaxWidth()) { text() }
        return
    }
    Row(modifier = modifier.fillMaxWidth().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
        BookCover(
            book = book,
            file = coverFile(book),
            modifier = Modifier.size(COVER_WIDTH_GRID_UNITS.gridUnitsAsDp(), COVER_HEIGHT_GRID_UNITS.gridUnitsAsDp()),
        )
        Spacer(modifier = Modifier.width(COVER_GAP_GRID_UNITS.gridUnitsAsDp()))
        Column(modifier = Modifier.weight(1f)) { text() }
    }
}

/** "Continue reading" (in the accent), the book's title, then its progress. */
@Composable
private fun ContinueRowView(row: LibraryRow) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LightText(
            text = "Continue reading",
            variant = LightTextVariant.Detail,
            color = LocalReaderAccent.current,
            maxLines = 1,
        )
        OneLine(text = row.meta.title, variant = LightTextVariant.Copy)
        OneLine(text = row.statusText, variant = LightTextVariant.Detail, lighten = true)
    }
}

@Composable
private fun BookRowView(row: LibraryRow, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        OneLine(text = row.meta.title, variant = LightTextVariant.Copy)
        if (row.canOpen) {
            OneLine(text = row.meta.author, variant = LightTextVariant.Detail, lighten = true)
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

/** Series name, author, then "N books · M finished" (lighter, so it reads as a count). */
@Composable
private fun SeriesRowView(series: LibraryEntry.Series, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        OneLine(text = series.name, variant = LightTextVariant.Copy)
        OneLine(text = series.author, variant = LightTextVariant.Detail, lighten = true)
        OneLine(text = series.countText, variant = LightTextVariant.Detail, lighten = true)
    }
}

@Composable
private fun OneLine(text: String, variant: LightTextVariant, lighten: Boolean = false) {
    LightText(
        text = text,
        variant = variant,
        lighten = lighten,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
