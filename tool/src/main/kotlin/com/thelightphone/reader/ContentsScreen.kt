package com.thelightphone.reader

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.reader.data.BookMeta
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * The book's chapters, reachable from the reading screen (CLAUDE.md 9). Tapping a chapter
 * hands its index back to the calling ReaderScreen via [goBack] rather than navigating
 * onward, so the jump happens in place instead of pushing a new reading screen onto the
 * back stack. It opens scrolled to the chapter being read. Headings ("Book One: Pale") are in
 * the accent color, and each level of the contents is indented further; tapping a heading
 * opens the first readable chapter under it. "Add to list" at the bottom opens
 * AddToListScreen for this book; "Details" opens BookDetailsScreen.
 */
class ContentsScreen(
    sealedActivity: SealedLightActivity,
    private val bookMeta: BookMeta,
    private val currentChapterIndex: Int,
) : SimpleLightScreen<Int>(sealedActivity) {

    @Composable
    override fun Content() {
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = { goBack() },
                ),
                center = LightTopBarCenter.Text(bookMeta.title),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            ChapterList(
                bookMeta = bookMeta,
                currentChapterIndex = currentChapterIndex,
                onSelect = { row -> goBack(row.chapterIndex) },
                modifier = Modifier.weight(1f),
            )
            LightBottomBar(
                items = listOf(
                    LightBarButton.Text(text = "ADD TO LIST", onClick = ::openAddToList),
                    LightBarButton.Text(text = "DETAILS", onClick = ::openDetails),
                ),
            )
        }
    }

    /** Nothing comes back: the lists are saved as they're switched on and off. */
    private fun openAddToList() {
        navigateTo(screenFactory = { AddToListScreen(it, bookMeta) })
    }

    private fun openDetails() {
        navigateTo(screenFactory = { BookDetailsScreen(it, bookMeta) })
    }
}

/**
 * Each chapter row is this tall (grid units), divider included. The title is one line
 * (about 2.3 units), so every row is the same height — the lazy list needs that.
 */
private const val CHAPTER_ROW_GRID_UNITS = 4f

/** Each level of the contents is indented this much (grid units), up to [MAX_INDENTED_DEPTH] levels. */
private const val INDENT_GRID_UNITS = 1.25f
private const val MAX_INDENTED_DEPTH = 4

/**
 * Only the rows on screen are drawn, so a book with hundreds of chapters opens at once.
 * The list opens with [currentChapterIndex] as its top row.
 */
@Composable
private fun ChapterList(
    bookMeta: BookMeta,
    currentChapterIndex: Int,
    onSelect: (ContentsRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(bookMeta) { contentsRows(bookMeta.chapters) }
    // A chapter's own row comes after the heading rows that lead to it.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = rows.indexOfLast { it.chapterIndex == currentChapterIndex }.coerceAtLeast(0),
    )
    LightLazyScrollView(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        listState = listState,
        uniformItemHeightGridUnits = CHAPTER_ROW_GRID_UNITS,
    ) {
        itemsIndexed(rows) { i, row ->
            UniformRow(
                heightGridUnits = CHAPTER_ROW_GRID_UNITS,
                showDivider = i != rows.lastIndex,
                modifier = Modifier.lightClickable { onSelect(row) },
            ) {
                LightText(
                    text = row.title,
                    variant = LightTextVariant.Copy,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (row.isHeading) LocalReaderAccent.current else null,
                    modifier = Modifier.padding(start = (row.depth.coerceAtMost(MAX_INDENTED_DEPTH) * INDENT_GRID_UNITS).gridUnitsAsDp()),
                )
            }
        }
    }
}
