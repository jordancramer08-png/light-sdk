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

    /** True when the status is "NN% read" (drawn in the theme's accent). */
    val isStarted: Boolean get() = canOpen && statusText.endsWith(READ_SUFFIX)
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
 * The library rows in the chosen order. Ties always fall back to the EPUB file name,
 * A to Z, so an author's series stays in order (01, 02, …) even when authors run Z to A.
 */
fun libraryRows(
    books: List<BookMeta>,
    positions: Map<String, ReadingPosition>,
    sort: LibrarySort = LibrarySort.DEFAULT,
): List<LibraryRow> =
    books
        .sortedWith(comparatorFor(sort).thenBy { it.source.fileName })
        .map { LibraryRow(it, statusText(it, positions[it.slug])) }

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
    return "$percent$READ_SUFFIX"
}
