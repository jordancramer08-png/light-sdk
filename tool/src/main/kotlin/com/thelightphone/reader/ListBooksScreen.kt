package com.thelightphone.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.DatabaseQueue
import com.thelightphone.reader.data.ReadingListRepository
import com.thelightphone.reader.data.readerDatabase
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Rearranges one list, reached from the pencil in the Library's top bar while a list is
 * showing. Each book has up and down arrows (none at the top or bottom) and an × that
 * takes it out of the list — the book itself stays on the phone. Every change is saved
 * straight away; back returns to the list.
 */
class ListBooksScreen(
    sealedActivity: SealedLightActivity,
    private val listId: Long,
    private val listName: String,
    books: List<BookMeta>,
) : SimpleLightScreen<Unit>(sealedActivity) {

    private val repository = ReadingListRepository.getInstance { lightContext.readerDatabase() }

    private var books by mutableStateOf(books)

    @Composable
    override fun Content() {
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(listName),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            if (books.isEmpty()) {
                EmptyListMessage()
            } else {
                EditableBookList(books = books, onMove = ::move, onRemove = ::remove)
            }
        }
    }

    /** Trades places with the book above ([up]) or below, on screen and in the database. */
    private fun move(book: BookMeta, up: Boolean) {
        val slugs = books.map { it.slug }
        val neighbour = neighbourSlug(slugs, book.slug, up) ?: return
        val from = slugs.indexOf(book.slug)
        val to = slugs.indexOf(neighbour)
        books = books.toMutableList().apply {
            this[from] = books[to]
            this[to] = book
        }
        DatabaseQueue.write { repository.swapBooks(listId, book.slug, neighbour) }
    }

    private fun remove(book: BookMeta) {
        books = books - book
        DatabaseQueue.write { repository.removeBook(listId, book.slug) }
    }
}

@Composable
private fun EmptyListMessage() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = "No books in this list.",
            variant = LightTextVariant.Copy,
            lighten = true,
            align = TextAlign.Center,
        )
    }
}

@Composable
private fun EditableBookList(
    books: List<BookMeta>,
    onMove: (BookMeta, up: Boolean) -> Unit,
    onRemove: (BookMeta) -> Unit,
) {
    LightScrollView(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        books.forEachIndexed { i, book ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 0.5f.gridUnitsAsDp()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    LightText(
                        text = book.title,
                        variant = LightTextVariant.Copy,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LightText(
                        text = book.author,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // No arrow at the top or bottom: the gap keeps the other icons lined up.
                if (i > 0) RowIconButton(icon = LightIcons.UP, onClick = { onMove(book, true) }) else RowIconGap()
                if (i < books.lastIndex) RowIconButton(icon = LightIcons.DOWN, onClick = { onMove(book, false) }) else RowIconGap()
                RowIconButton(icon = LightIcons.CLOSE, onClick = { onRemove(book) })
            }
            if (i != books.lastIndex) {
                HairlineDivider()
            }
        }
    }
}
