package com.thelightphone.reader

import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.BookProblem
import com.thelightphone.reader.data.ReadingPosition
import java.text.Normalizer

/**
 * What the library list shows, worked out in plain Kotlin so it can be unit-tested
 * on the PC (CLAUDE.md 8). No Android or Compose here.
 */

/** One row on the library screen. */
data class LibraryRow(
    val meta: BookMeta,
    /** "Not started", "NN% read", or why the book can't be opened. */
    val statusText: String,
) {
    val canOpen: Boolean get() = meta.problem == null
}

/** Sorted by author (ignoring case and accents), then by EPUB file name, which keeps series in order. */
fun libraryRows(books: List<BookMeta>, positions: Map<String, ReadingPosition>): List<LibraryRow> =
    books
        .sortedWith(compareBy<BookMeta>({ sortKey(it.author) }, { it.source.fileName }))
        .map { LibraryRow(it, statusText(it, positions[it.slug])) }

/** "le Carré" and "Le Carre" both become "le carre". */
fun sortKey(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .trim()

fun statusText(meta: BookMeta, position: ReadingPosition?): String = when (meta.problem) {
    BookProblem.DRM -> "Can't open (DRM)"
    BookProblem.UNREADABLE -> "Can't open"
    null -> progressText(meta, position)
}

/**
 * How far through the book the saved place is, by characters (as the September app
 * did it): all earlier chapters plus the offset into the current one.
 */
fun progressText(meta: BookMeta, position: ReadingPosition?): String {
    if (position == null) return "Not started"
    val totalChars = meta.chapters.sumOf { it.chars.toLong() }
    if (totalChars <= 0) return "Not started"
    val readChars = meta.chapters
        .filter { it.index < position.chapterIndex }
        .sumOf { it.chars.toLong() } + position.charOffset
    val percent = (readChars * 100 / totalChars).coerceIn(0, 100)
    return "$percent% read"
}
