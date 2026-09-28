package com.thelightphone.reader

import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.ReadingPosition
import java.math.BigDecimal

/**
 * Series grouping in plain Kotlin, so it can be unit-tested on the PC (CLAUDE.md 8).
 * No Android or Compose here.
 */

/** One row on the library screen: a single book, or a whole series shown as one row. */
sealed interface LibraryEntry {
    /** What the row is sorted by, and the lazy list's key. */
    val title: String
    val author: String
    val fileName: String
    val key: String

    /** When its book (a series: its most recent book) was last read, or null if never. */
    val lastReadAt: Long?

    /** When its EPUB (a series: its newest one) was put on the phone. */
    val addedAt: Long

    /** The book whose cover the row shows (a series: its first book). */
    val coverBook: BookMeta

    data class Book(val row: LibraryRow) : LibraryEntry {
        override val title get() = row.meta.title
        override val author get() = row.meta.author
        override val fileName get() = row.meta.source.fileName
        override val key get() = row.meta.slug
        override val lastReadAt get() = row.lastReadAt
        override val addedAt get() = row.meta.source.modified
        override val coverBook get() = row.meta
    }

    /** Two or more books of one series, [rows] in number order. */
    data class Series(val name: String, val rows: List<LibraryRow>) : LibraryEntry {
        override val title get() = name
        override val author get() = rows.first().meta.author
        override val fileName get() = rows.first().meta.source.fileName

        // Slugs never hold a ":", so this can't clash with a book's key.
        override val key get() = "series:" + sortKey(name)
        override val lastReadAt get() = rows.mapNotNull { it.lastReadAt }.maxOrNull()
        override val addedAt get() = rows.maxOf { it.meta.source.modified }
        override val coverBook get() = rows.first().meta

        val finishedCount: Int get() = rows.count { it.status == ReadingStatus.FINISHED }

        /** "3 books · 1 finished". */
        val countText: String get() = "${rows.size} books · $finishedCount finished"
    }
}

/**
 * The library's entries: the same books [libraryRows] shows, in the chosen order. With
 * [groupSeries] on, books of one series (same name, ignoring case and accents) become a
 * single entry, sorted by the series name (title orders), its first book's author, or its
 * most recently read or added book (the Recently orders). A
 * series with only one book shown stays a normal book row. The filter is applied first, so
 * a series row counts only the books the filter lets through.
 */
fun libraryEntries(
    books: List<BookMeta>,
    positions: Map<String, ReadingPosition>,
    sort: LibrarySort = LibrarySort.DEFAULT,
    statuses: Map<String, ReadingStatus> = emptyMap(),
    filter: LibraryFilter = LibraryFilter.DEFAULT,
    groupSeries: Boolean = true,
): List<LibraryEntry> {
    val rows = libraryRows(books, positions, sort, statuses, filter)
    if (!groupSeries) return rows.map { LibraryEntry.Book(it) }

    val bySeries = rows.filter { it.meta.series != null }.groupBy { sortKey(it.meta.series!!) }
    val entries = rows.mapNotNull { row ->
        val members = row.meta.series?.let { bySeries[sortKey(it)] }
        when {
            members == null || members.size < 2 -> LibraryEntry.Book(row)
            row === members.first() -> seriesEntry(members) // one entry per series
            else -> null
        }
    }
    return entries.sortedWith(libraryOrder(sort))
}

/** The series row, named after its first book (in number order). */
private fun seriesEntry(members: List<LibraryRow>): LibraryEntry.Series {
    val inOrder = members.sortedWith(seriesOrder)
    return LibraryEntry.Series(inOrder.first().meta.series!!, inOrder)
}

/** Book 1, 2, 2.5, 3 …; books with no number go last. Ties fall back to the file name. */
private val seriesOrder: Comparator<LibraryRow> =
    compareBy<LibraryRow, BigDecimal?>(nullsLast(naturalOrder())) { it.meta.seriesNumber?.toBigDecimalOrNull() }
        .thenBy { it.meta.source.fileName }
