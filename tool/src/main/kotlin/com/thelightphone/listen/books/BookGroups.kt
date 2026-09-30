package com.thelightphone.listen.books

import com.thelightphone.listen.music.sortKey

/** What the audiobook library can be sorted by. */
enum class BookSortField(val label: String) { TITLE("Title"), AUTHOR("Author"), RECENT("Recently played") }

/** One row of the library: a single book, or a whole series shown as one row (as in the Reader). */
sealed interface BookEntry {
    /** The lazy list's key. */
    val key: String
    val title: String
    val author: String

    /** When its book (a series: its most recently played book) was last played; 0 = never. */
    val lastPlayedAt: Long

    /** The book whose cover the row shows (a series: its first book). */
    val coverBook: Book

    data class Single(val book: Book, val position: BookPosition?) : BookEntry {
        override val key get() = "book:" + book.id
        override val title get() = book.title
        override val author get() = book.author
        override val lastPlayedAt get() = position?.lastPlayedAt ?: 0
        override val coverBook get() = book
    }

    /** Two or more books of one series by one author, [books] in series-number order. */
    data class Series(
        override val key: String,
        val name: String,
        val books: List<Book>,
        val positions: Map<String, BookPosition>,
    ) : BookEntry {
        override val title get() = name
        override val author get() = books.first().author
        override val lastPlayedAt get() = books.maxOf { positions[it.id]?.lastPlayedAt ?: 0 }
        override val coverBook get() = books.first()

        val finishedCount: Int get() = books.count { isFinished(it, positions[it.id]) }

        /** "3 books · 1 finished". */
        val countText: String get() = "${books.size} books · $finishedCount finished"
    }
}

/** Books of one series by one author share this key (case, accents and "The" ignored). */
fun seriesKey(book: Book): String? =
    book.series?.let { "series:" + sortKey(book.author) + "|" + sortKey(it) }

/** Book 1, 2, 2.5, 3 …; books without a number go last, then by title. */
val SERIES_ORDER: Comparator<Book> =
    compareBy<Book, Double?>(nullsLast(naturalOrder())) { it.seriesNumber }
        .thenBy { sortKey(it.title) }
        .thenBy { it.folder }

/**
 * The library's rows in the chosen order. Books of one series (two or more on the phone)
 * become one series row; a series with a single book on the phone stays a book row.
 *  - Title: by book or series name.
 *  - Author: by author, then each author's series and books by name (so an author's books
 *    sit together). Only the author order is reversed for Z–A.
 *  - Recently played: newest first (Z–A: oldest first); never-played books always last, by name.
 */
fun libraryEntries(
    books: List<Book>,
    positions: Map<String, BookPosition>,
    field: BookSortField,
    descending: Boolean,
): List<BookEntry> {
    val bySeries = books.filter { seriesKey(it) != null }.groupBy { seriesKey(it)!! }
    val entries = mutableListOf<BookEntry>()
    val seriesDone = mutableSetOf<String>()
    for (book in books) {
        val key = seriesKey(book)
        val members = key?.let { bySeries[it] }
        when {
            members == null || members.size < 2 -> entries += BookEntry.Single(book, positions[book.id])
            seriesDone.add(key) -> {
                val inOrder = members.sortedWith(SERIES_ORDER)
                entries += BookEntry.Series(key, inOrder.first().series!!, inOrder, positions)
            }
        }
    }
    return entries.sortedWith(entryOrder(field, descending))
}

private fun entryOrder(field: BookSortField, descending: Boolean): Comparator<BookEntry> {
    val byTitle = compareBy<BookEntry> { sortKey(it.title) }
    val byAuthor = compareBy<BookEntry> { sortKey(it.author) }
    val order: Comparator<BookEntry> = when (field) {
        BookSortField.TITLE -> (if (descending) byTitle.reversed() else byTitle).then(byAuthor)
        BookSortField.AUTHOR -> (if (descending) byAuthor.reversed() else byAuthor).then(byTitle)
        BookSortField.RECENT -> {
            val never = compareBy<BookEntry> { it.lastPlayedAt == 0L }
            val newest = compareByDescending<BookEntry> { it.lastPlayedAt }
            never.then(if (descending) newest.reversed() else newest).then(byTitle)
        }
    }
    return order.thenBy { it.key }
}

/** "Continue listening": books started and not finished, most recently played first. */
fun continueListening(books: List<Book>, positions: Map<String, BookPosition>): List<Book> =
    books
        .filter { isInProgress(it, positions[it.id]) }
        .sortedWith(compareByDescending<Book> { positions[it.id]?.lastPlayedAt ?: 0 }.thenBy { sortKey(it.title) })

/** A series' books in number order, found by [seriesKey]. */
fun seriesBooks(books: List<Book>, key: String): List<Book> =
    books.filter { seriesKey(it) == key }.sortedWith(SERIES_ORDER)
