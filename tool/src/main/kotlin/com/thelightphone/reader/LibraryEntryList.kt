package com.thelightphone.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.reader.data.BookMeta
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

/**
 * The Library's list, also used by the Series screen. Only the rows on screen are drawn,
 * so a long library scrolls smoothly. A book row opens the book; a series row opens the
 * series.
 */
@Composable
fun LibraryEntryList(
    entries: List<LibraryEntry>,
    onSelectBook: (BookMeta) -> Unit,
    onSelectSeries: (LibraryEntry.Series) -> Unit = {},
) {
    LightLazyScrollView(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        uniformItemHeightGridUnits = ENTRY_ROW_GRID_UNITS,
    ) {
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
                when (entry) {
                    is LibraryEntry.Book -> BookRowView(row = entry.row, modifier = Modifier.fillMaxWidth())
                    is LibraryEntry.Series -> SeriesRowView(series = entry, modifier = Modifier.fillMaxWidth())
                }
            }
        }
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
