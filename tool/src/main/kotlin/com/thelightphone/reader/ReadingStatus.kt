package com.thelightphone.reader

/**
 * Reading status in plain Kotlin, so it can be unit-tested on the PC (CLAUDE.md 9).
 * No Android here.
 */

/** Where Jordan is with a book. A book with nothing saved yet is Want to Read. */
enum class ReadingStatus(val label: String) {
    WANT_TO_READ("Want to Read"),
    READING("Reading"),
    FINISHED("Finished");

    companion object {
        val DEFAULT = WANT_TO_READ

        /** Turns a saved name back into a status; anything unknown means Want to Read. */
        fun fromSavedName(name: String?): ReadingStatus =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Opening a book makes it Reading, but only the first time: a Finished book being
 * re-read stays Finished. Null means "no change".
 */
fun statusAfterOpening(current: ReadingStatus): ReadingStatus? =
    if (current == ReadingStatus.WANT_TO_READ) ReadingStatus.READING else null

/** The Show choices on the Sort & Filter screen. */
enum class LibraryFilter(val label: String, private val status: ReadingStatus?) {
    ALL("All", null),
    WANT_TO_READ("Want to Read", ReadingStatus.WANT_TO_READ),
    READING("Reading", ReadingStatus.READING),
    FINISHED("Finished", ReadingStatus.FINISHED);

    fun shows(bookStatus: ReadingStatus): Boolean = status == null || status == bookStatus

    companion object {
        val DEFAULT = ALL

        /** Turns a saved name back into a choice; anything unknown means All. */
        fun fromSavedName(name: String?): LibraryFilter =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** What the library shows when a filter leaves nothing, e.g. "No books marked Finished." */
fun emptyFilterText(filter: LibraryFilter): String =
    if (filter == LibraryFilter.ALL) "No books on this device yet." else "No books marked ${filter.label}."
