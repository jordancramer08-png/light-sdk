package com.thelightphone.listen.books

import kotlinx.serialization.Serializable

/**
 * A chapter mark inside one file (only m4b files with chapter marks have them): its
 * [title] and where it starts in that file.
 */
@Serializable
data class ChapterMark(val title: String, val startMs: Long = 0)

/**
 * One audio file of a book, in play order. [path] is relative to the book folder, with "/"
 * separators. [size] and [modified] tell the scanner whether the file changed since its
 * length was read. [tagTitle] and [tagArtist] are the file's own tags, kept for books
 * without a book.json.
 */
@Serializable
data class BookFile(
    val path: String,
    val label: String,
    val size: Long,
    val modified: Long,
    val durationMs: Long = 0,
    val chapters: List<ChapterMark> = emptyList(),
    val tagTitle: String? = null,
    val tagArtist: String? = null,
)

/**
 * One audiobook: a folder under /sdcard/Listen/Audiobooks, as saved in the index cache.
 * [id] is book.json's id (or the folder path when there's no book.json); positions are
 * keyed by it, so a book removed and sent again resumes where it was. [folder] is relative
 * to Audiobooks/. [coverFile] is the cover picture named by book.json, relative to the
 * folder, when that file is on the phone. [hasBookJson] is false when the details came from
 * the folder names and file tags instead.
 */
@Serializable
data class Book(
    val id: String,
    val folder: String,
    val title: String,
    val author: String,
    val narrator: String = "",
    val series: String? = null,
    val seriesNumber: Double? = null,
    val year: String = "",
    val coverFile: String? = null,
    val coverModified: Long = 0,
    val files: List<BookFile> = emptyList(),
    val hasBookJson: Boolean = true,
) {
    /** The whole book's length (0 when no file's length is known). */
    val durationMs: Long get() = files.sumOf { it.durationMs }
}

/** "Book 2", "Book 2.5". */
fun seriesNumberText(number: Double?): String? = number?.let {
    if (it == Math.floor(it) && !it.isInfinite()) it.toLong().toString() else it.toString()
}

/** "Dune Chronicles, book 2", or just the series name without a number. Null without a series. */
fun seriesLine(book: Book): String? {
    val series = book.series ?: return null
    val number = seriesNumberText(book.seriesNumber) ?: return series
    return "$series, book $number"
}

const val UNKNOWN_AUTHOR = "Unknown author"
