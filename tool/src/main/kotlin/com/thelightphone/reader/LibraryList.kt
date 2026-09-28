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
    /** "Not started", "NN% read", "Finished", or why the book can't be opened. */
    val statusText: String,
    val status: ReadingStatus = ReadingStatus.DEFAULT,
) {
    val canOpen: Boolean get() = meta.problem == null

    /** True when the row shows "NN% read" or "Finished" (drawn in the theme's accent). */
    val isStarted: Boolean
        get() = canOpen && (statusText.endsWith(READ_SUFFIX) || status == ReadingStatus.FINISHED)
}

/** One book's row. [statuses] holds only books with a saved status; the rest are Want to Read. */
fun libraryRow(
    meta: BookMeta,
    positions: Map<String, ReadingPosition>,
    statuses: Map<String, ReadingStatus>,
): LibraryRow {
    val status = statuses[meta.slug] ?: ReadingStatus.DEFAULT
    return LibraryRow(meta, statusText(meta, positions[meta.slug], status), status)
}

private const val READ_SUFFIX = "% read"

/** The four ways the library can be ordered, as shown on the sort screen. */
enum class LibrarySort(val label: String) {
    AUTHOR_A_TO_Z("Author A–Z"),
    AUTHOR_Z_TO_A("Author Z–A"),
    TITLE_A_TO_Z("Title A–Z"),
    TITLE_Z_TO_A("Title Z–A");

    companion object {
        val DEFAULT = AUTHOR_A_TO_Z

        /** Turns a saved name back into a choice; anything unknown falls back to the default. */
        fun fromSavedName(name: String?): LibrarySort =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * The library rows in the chosen order, keeping only the books [filter] shows. Ties always
 * fall back to the EPUB file name, A to Z, so an author's series stays in order (01, 02, …)
 * even when authors run Z to A.
 */
fun libraryRows(
    books: List<BookMeta>,
    positions: Map<String, ReadingPosition>,
    sort: LibrarySort = LibrarySort.DEFAULT,
    statuses: Map<String, ReadingStatus> = emptyMap(),
    filter: LibraryFilter = LibraryFilter.DEFAULT,
): List<LibraryRow> =
    books
        .sortedWith(comparatorFor(sort).thenBy { it.source.fileName })
        .map { libraryRow(it, positions, statuses) }
        .filter { filter.shows(it.status) }

private fun comparatorFor(sort: LibrarySort): Comparator<BookMeta> = when (sort) {
    LibrarySort.AUTHOR_A_TO_Z -> compareBy { sortKey(it.author) }
    LibrarySort.AUTHOR_Z_TO_A -> compareByDescending { sortKey(it.author) }
    LibrarySort.TITLE_A_TO_Z -> compareBy { titleSortKey(it.title) }
    LibrarySort.TITLE_Z_TO_A -> compareByDescending { titleSortKey(it.title) }
}

private val LEADING_ARTICLE = Regex("^(the|a|an)\\s+")

/** "The Shadow of the Wind" sorts under S, "A Wizard of Earthsea" under W. */
fun titleSortKey(title: String): String =
    sortKey(title).replaceFirst(LEADING_ARTICLE, "")

/** "le Carré" and "Le Carre" both become "le carre". */
fun sortKey(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .trim()

/** A Finished book says so instead of a percentage. */
fun statusText(
    meta: BookMeta,
    position: ReadingPosition?,
    status: ReadingStatus = ReadingStatus.DEFAULT,
): String = when {
    meta.problem == BookProblem.DRM -> "Can't open (DRM)"
    meta.problem == BookProblem.UNREADABLE -> "Can't open"
    status == ReadingStatus.FINISHED -> ReadingStatus.FINISHED.label
    else -> progressText(meta, position)
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
    return "$percent$READ_SUFFIX"
}
