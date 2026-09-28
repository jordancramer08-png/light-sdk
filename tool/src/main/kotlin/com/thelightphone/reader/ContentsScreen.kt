package com.thelightphone.reader

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.ChapterMeta
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
 * back stack. It opens scrolled to the chapter being read. "Add to list" at the bottom opens
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
                onSelect = { chapter -> goBack(chapter.index) },
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

/**
 * Only the rows on screen are drawn, so a book with hundreds of chapters opens at once.
 * The list opens with [currentChapterIndex] as its top row.
 */
@Composable
private fun ChapterList(
    bookMeta: BookMeta,
    currentChapterIndex: Int,
    onSelect: (ChapterMeta) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chapters = bookMeta.chapters
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = chapters.indexOfFirst { it.index == currentChapterIndex }.coerceAtLeast(0),
    )
    LightLazyScrollView(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        listState = listState,
        uniformItemHeightGridUnits = CHAPTER_ROW_GRID_UNITS,
    ) {
        itemsIndexed(chapters, key = { _, chapter -> chapter.index }) { i, chapter ->
            UniformRow(
                heightGridUnits = CHAPTER_ROW_GRID_UNITS,
                showDivider = i != chapters.lastIndex,
                modifier = Modifier.lightClickable { onSelect(chapter) },
            ) {
                LightText(
                    text = chapter.title,
                    variant = LightTextVariant.Copy,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
