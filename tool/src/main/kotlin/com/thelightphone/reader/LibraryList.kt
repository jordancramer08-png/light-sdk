package com.thelightphone.reader

import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.BookProblem
import com.thelightphone.reader.data.ChapterMeta
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
    /** When the book was last open in the reader (its saved place's time), or null if never. */
    val lastReadAt: Long? = null,
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
    val position = positions[meta.slug]
    return LibraryRow(meta, statusText(meta, position, status), status, position?.updatedAt)
}

private const val READ_SUFFIX = "% read"

/** The ways the library can be ordered, as shown on the sort screen. */
enum class LibrarySort(val label: String) {
    AUTHOR_A_TO_Z("Author A–Z"),
    AUTHOR_Z_TO_A("Author Z–A"),
    TITLE_A_TO_Z("Title A–Z"),
    TITLE_Z_TO_A("Title Z–A"),

    /** Last opened first; books never opened come after, by title. */
    RECENTLY_READ("Recently read"),

    /** Newest EPUB file first (its modified time: the day the send script copied it). */
    RECENTLY_ADDED("Recently added");

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
        .map { libraryRow(it, positions, statuses) }
        .filter { filter.shows(it.status) }
        .sortedWith(compareBy(libraryOrder(sort)) { LibraryEntry.Book(it) })

/**
 * The order of library entries, books and series rows alike (a series sorts by its name,
 * its first book's author, and its most recently read or added book). Ties fall back to the
 * file name, A to Z.
 */
fun libraryOrder(sort: LibrarySort): Comparator<LibraryEntry> {
    val order: Comparator<LibraryEntry> = when (sort) {
        LibrarySort.AUTHOR_A_TO_Z -> compareBy { sortKey(it.author) }
        LibrarySort.AUTHOR_Z_TO_A -> compareByDescending { sortKey(it.author) }
        LibrarySort.TITLE_A_TO_Z -> compareBy { titleSortKey(it.title) }
        LibrarySort.TITLE_Z_TO_A -> compareByDescending { titleSortKey(it.title) }
        LibrarySort.RECENTLY_READ ->
            compareBy<LibraryEntry, Long?>(nullsLast(reverseOrder())) { it.lastReadAt }.thenBy { titleSortKey(it.title) }
        LibrarySort.RECENTLY_ADDED -> compareByDescending { it.addedAt }
    }
    return order.thenBy { it.fileName }
}

/**
 * The "Continue reading" row: the book opened most recently. Null when no book on the phone
 * has been opened yet, or when [filter] hides that book (the next most recent isn't used
 * instead: the row is always the last book read).
 */
fun continueReadingRow(
    books: List<BookMeta>,
    positions: Map<String, ReadingPosition>,
    statuses: Map<String, ReadingStatus> = emptyMap(),
    filter: LibraryFilter = LibraryFilter.DEFAULT,
): LibraryRow? {
    val latest = books
        .map { libraryRow(it, positions, statuses) }
        .filter { it.canOpen && it.lastReadAt != null }
        .maxWithOrNull(compareBy<LibraryRow> { it.lastReadAt }.thenByDescending { it.meta.source.fileName })
    return latest?.takeIf { filter.shows(it.status) }
}

/** The letter on a book's placeholder cover: its title's first letter or digit, or "?". */
fun coverLetter(title: String): String =
    title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

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
    val percent = percentRead(meta.chapters, position.chapterIndex, position.charOffset) ?: return "Not started"
    return "$percent$READ_SUFFIX"
}

/**
 * The percent read at [charOffset] into chapter [chapterIndex]: all earlier chapters plus
 * the offset, over the whole book. Null for a book with no text. The reader's progress line
 * uses it too, so it always agrees with the library.
 */
fun percentRead(chapters: List<ChapterMeta>, chapterIndex: Int, charOffset: Int): Int? {
    val totalChars = chapters.sumOf { it.chars.toLong() }
    if (totalChars <= 0) return null
    val readChars = chapters.filter { it.index < chapterIndex }.sumOf { it.chars.toLong() } + charOffset
    return (readChars * 100 / totalChars).coerceIn(0, 100).toInt()
}
