package com.jordan.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.jordan.watch.logic.Shelves
import com.jordan.watch.logic.Tile
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/*
 * The library's long lists. LightLazyScrollView only draws the rows on screen and works
 * out its scrollbar from one fixed row height, so every row here is exactly its constant
 * tall: a grid row of 2 or 3 posters, or a list row with a small poster.
 */

/** List rows: small poster, title, detail line, and a progress bar when started. */
const val LIST_ROW_GRID_UNITS = 5f
private const val LIST_POSTER_HEIGHT_GRID_UNITS = 4.4f

/** Grid rows: poster (2:3) and one title line, sized for the 1080 px wide screen. */
fun gridRowGridUnits(columns: Int): Float = if (columns == 2) 19f else 13.5f

private const val GAP_GRID_UNITS = 0.5f
private const val PROGRESS_PX = 8f

/** A lazy list of fixed-height rows; [tag] changing (another tab or view) starts it at the top. */
@Composable
fun WatchLazyList(tag: Any, rowGridUnits: Float, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    key(tag) {
        LightLazyScrollView(
            modifier = modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()),
            uniformItemHeightGridUnits = rowGridUnits,
            content = content,
        )
    }
}

/** [tiles] as grid rows of [columns] posters. */
fun LazyListScope.posterGrid(tiles: List<Tile>, columns: Int, onOpen: (Tile) -> Unit) {
    val rows = Shelves.gridRows(tiles, columns)
    itemsIndexed(rows, key = { _, row -> "grid:" + row.first().id }) { _, row ->
        Row(
            modifier = Modifier.fillMaxWidth().height(gridRowGridUnits(columns).gridUnitsAsDp())
                .padding(bottom = GAP_GRID_UNITS.gridUnitsAsDp()),
        ) {
            for (i in 0 until columns) {
                if (i > 0) Spacer(Modifier.width(GAP_GRID_UNITS.gridUnitsAsDp()))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    row.getOrNull(i)?.let { GridCell(it, onOpen) }
                }
            }
        }
    }
}

@Composable
private fun GridCell(tile: Tile, onOpen: (Tile) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().lightClickable { onOpen(tile) }) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PosterImage(tile, Modifier.fillMaxSize())
            PosterMarks(tile)
        }
        OneLine(tile.title, LightTextVariant.Detail)
    }
}

/** On a poster: the watched tick (top right) and the progress bar (along the bottom). */
@Composable
private fun BoxScope.PosterMarks(tile: Tile) {
    if (tile.watched) {
        Box(
            modifier = Modifier.align(Alignment.TopEnd).padding(0.2f.gridUnitsAsDp())
                .background(LightThemeTokens.colors.background.copy(alpha = 0.7f)),
        ) {
            LightIcon(icon = LightIcons.ACCEPT, size = 1.2f)
        }
    }
    tile.progress?.let { ProgressBar(it, Modifier.align(Alignment.BottomCenter)) }
}

/** A thin bar: the accent part is how far in, on a dim track the full width. */
@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().height(PROGRESS_PX.designVerticalPxToDp())
            .background(LightThemeTokens.colors.contentSecondary.copy(alpha = 0.5f)),
    ) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(LocalAccent.current))
    }
}

/** [tiles] as rows with a small poster, title, detail and (when started) a progress bar. */
fun LazyListScope.posterList(tiles: List<Tile>, onOpen: (Tile) -> Unit, keyPrefix: String = "list:") {
    itemsIndexed(tiles, key = { _, tile -> keyPrefix + tile.id }) { _, tile -> ListRow(tile, onOpen) }
}

@Composable
fun ListRow(tile: Tile, onOpen: (Tile) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(LIST_ROW_GRID_UNITS.gridUnitsAsDp()).lightClickable { onOpen(tile) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.height(LIST_POSTER_HEIGHT_GRID_UNITS.gridUnitsAsDp()).width((LIST_POSTER_HEIGHT_GRID_UNITS * 2 / 3).gridUnitsAsDp())) {
            PosterImage(tile, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(0.75f.gridUnitsAsDp()))
        Column(Modifier.weight(1f)) {
            OneLine(tile.title, LightTextVariant.Copy)
            OneLine(tile.detail + if (tile.watched) " · Watched" else "", LightTextVariant.Detail, lighten = true)
            tile.progress?.let {
                Spacer(Modifier.height(0.3f.gridUnitsAsDp()))
                ProgressBar(it)
            }
        }
    }
}

/** A section heading inside a lazy list, the same height as a list row. */
fun LazyListScope.headingRow(title: String) {
    item(key = "heading:$title") {
        Box(Modifier.fillMaxWidth().height(LIST_ROW_GRID_UNITS.gridUnitsAsDp()), contentAlignment = Alignment.BottomStart) {
            LightText(text = title, variant = LightTextVariant.Subheading, modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()))
        }
    }
}

/** One line of text that ends in "..." when it's too long. */
@Composable
fun OneLine(text: String, variant: LightTextVariant, modifier: Modifier = Modifier, lighten: Boolean = false) {
    LightText(text = text, variant = variant, lighten = lighten, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** A lighter centered message filling the space it's given (empty tab, no access). */
@Composable
fun CenteredMessage(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 2f.gridUnitsAsDp()), contentAlignment = Alignment.Center) {
        LightText(text = message, variant = LightTextVariant.Paragraph, lighten = true)
    }
}
