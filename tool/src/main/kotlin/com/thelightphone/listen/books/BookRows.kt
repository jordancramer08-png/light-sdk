package com.thelightphone.listen.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.listen.artwork.ArtImage
import com.thelightphone.listen.artwork.ArtSize
import com.thelightphone.listen.artwork.artLetter
import com.thelightphone.listen.artwork.artSource
import com.thelightphone.listen.music.ArtAndText
import com.thelightphone.listen.ui.LocalListenAccent
import com.thelightphone.listen.ui.OneLine
import com.thelightphone.listen.ui.UniformRow
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** Book and series rows (grid units, divider included): three lines beside the cover. */
const val BOOK_ROW_GRID_UNITS = 7f
private const val BOOK_ART_GRID_UNITS = 5.6f

/** How strongly a Continue listening row is tinted with the accent (as in the Reader). */
private const val CONTINUE_TINT_ALPHA = 0.12f

/** "Continue listening" rows: tinted, the label in the accent, the title, then the progress. */
fun LazyListScope.continueRows(
    books: List<Book>,
    positions: Map<String, BookPosition>,
    moreFollow: Boolean,
    onOpen: (Book) -> Unit,
) {
    itemsIndexed(books, key = { _, book -> "continue:" + book.id }) { index, book ->
        UniformRow(
            heightGridUnits = BOOK_ROW_GRID_UNITS,
            showDivider = moreFollow || index != books.lastIndex,
            modifier = Modifier
                .background(LocalListenAccent.current.copy(alpha = CONTINUE_TINT_ALPHA))
                .lightClickable { onOpen(book) },
        ) {
            BookArtAndText(book, modifier = Modifier.padding(horizontal = 0.5f.gridUnitsAsDp())) {
                AccentLine("Continue listening")
                OneLine(text = book.title, variant = LightTextVariant.Copy)
                ProgressLine(book, positions[book.id])
            }
        }
    }
}

/** The library's rows: a book opens its book screen, a series row its series. */
fun LazyListScope.entryRows(
    entries: List<BookEntry>,
    onOpenBook: (Book) -> Unit,
    onOpenSeries: (BookEntry.Series) -> Unit,
) {
    itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
        UniformRow(
            heightGridUnits = BOOK_ROW_GRID_UNITS,
            showDivider = index != entries.lastIndex,
            modifier = Modifier.lightClickable {
                when (entry) {
                    is BookEntry.Single -> onOpenBook(entry.book)
                    is BookEntry.Series -> onOpenSeries(entry)
                }
            },
        ) {
            BookArtAndText(entry.coverBook) {
                when (entry) {
                    is BookEntry.Single -> {
                        OneLine(text = entry.book.title, variant = LightTextVariant.Copy)
                        val series = seriesLine(entry.book)
                        OneLine(
                            text = listOfNotNull(entry.book.author, series).joinToString(" · "),
                            variant = LightTextVariant.Detail,
                            lighten = true,
                        )
                        ProgressLine(entry.book, entry.position)
                    }
                    is BookEntry.Series -> {
                        OneLine(text = entry.name, variant = LightTextVariant.Copy)
                        OneLine(text = entry.author, variant = LightTextVariant.Detail, lighten = true)
                        OneLine(text = entry.countText, variant = LightTextVariant.Detail, lighten = true)
                    }
                }
            }
        }
    }
}

/** A series' books in number order: title, "Book 2", then the progress. */
fun LazyListScope.seriesBookRows(
    books: List<Book>,
    positions: Map<String, BookPosition>,
    onOpen: (Book) -> Unit,
) {
    itemsIndexed(books, key = { _, book -> "book:" + book.id }) { index, book ->
        UniformRow(
            heightGridUnits = BOOK_ROW_GRID_UNITS,
            showDivider = index != books.lastIndex,
            modifier = Modifier.lightClickable { onOpen(book) },
        ) {
            BookArtAndText(book) {
                OneLine(text = book.title, variant = LightTextVariant.Copy)
                OneLine(
                    text = seriesNumberText(book.seriesNumber)?.let { "Book $it" } ?: book.author,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
                ProgressLine(book, positions[book.id])
            }
        }
    }
}

@Composable
private fun BookArtAndText(book: Book, modifier: Modifier = Modifier, text: @Composable () -> Unit) {
    ArtAndText(
        art = { size -> ArtImage(book.artSource(), ArtSize.THUMB, artLetter(book.title), size) },
        artGridUnits = BOOK_ART_GRID_UNITS,
        modifier = modifier,
        text = text,
    )
}

/**
 * A row's progress: "43% · 6 hr 50 min left" or "Finished" in the accent; the book's length
 * (lighter) when it hasn't been started.
 */
@Composable
fun ProgressLine(book: Book, position: BookPosition?) {
    val progress = progressText(book, position)
    when {
        progress == null -> OneLine(
            text = if (book.durationMs > 0) bookLengthText(book.durationMs) else "Not started",
            variant = LightTextVariant.Detail,
            lighten = true,
        )
        isFinished(book, position) -> AccentLine(progress)
        else -> AccentLine(
            listOfNotNull(progress, timeLeftMs(book, position)?.let { bookLengthText(it) + " left" }).joinToString(" · "),
        )
    }
}

/** One line of small text in the theme's accent (it always says what it is in words too). */
@Composable
fun AccentLine(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        color = LocalListenAccent.current,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** "1 book", "12 books". */
fun bookCount(count: Int): String = if (count == 1) "1 book" else "%,d books".format(count)
