package com.thelightphone.listen.books

import com.thelightphone.listen.playback.formatTime

/** A book counts as finished once the place is within this of the end. */
const val FINISHED_WITHIN_MS = 30_000L

/**
 * One chapter of a book, across its files: file [fileIndex], from [startMs] to [endMs] in
 * that file ([endMs] is null when the file's length isn't known).
 */
data class BookChapter(
    val title: String,
    val fileIndex: Int,
    val startMs: Long,
    val endMs: Long?,
) {
    val durationMs: Long? get() = endMs?.let { (it - startMs).coerceAtLeast(0) }
}

/**
 * The book's chapters in order: an m4b's chapter marks, otherwise one chapter per file,
 * named by its label. A chapter with no title is "Chapter N".
 */
fun chaptersOf(book: Book): List<BookChapter> {
    val chapters = mutableListOf<BookChapter>()
    book.files.forEachIndexed { fileIndex, file ->
        val fileEnd = file.durationMs.takeIf { it > 0 }
        if (file.chapters.isEmpty()) {
            chapters += BookChapter(file.label, fileIndex, 0, fileEnd)
        } else {
            file.chapters.forEachIndexed { i, mark ->
                val end = file.chapters.getOrNull(i + 1)?.startMs ?: fileEnd
                chapters += BookChapter(mark.title, fileIndex, mark.startMs, end)
            }
        }
    }
    return chapters.mapIndexed { i, chapter ->
        if (chapter.title.isBlank()) chapter.copy(title = "Chapter ${i + 1}") else chapter
    }
}

/** How far into the whole book [position] is. */
fun bookPositionMs(book: Book, position: BookPosition): Long {
    val index = position.fileIndex.coerceIn(0, (book.files.size - 1).coerceAtLeast(0))
    return book.files.take(index).sumOf { it.durationMs } + position.positionMs.coerceAtLeast(0)
}

/** Whether the book is done: marked finished, or its place is in the last 30 seconds. */
fun isFinished(book: Book, position: BookPosition?): Boolean {
    if (position == null) return false
    if (position.finished) return true
    val total = book.durationMs
    return total > 0 && total - bookPositionMs(book, position) <= FINISHED_WITHIN_MS
}

/** Started and not finished: a "Continue listening" book. */
fun isInProgress(book: Book, position: BookPosition?): Boolean =
    position != null && !isFinished(book, position) && (position.fileIndex > 0 || position.positionMs > 0)

/** 0–100, or null when the book's length isn't known. */
fun percentListened(book: Book, position: BookPosition): Int? {
    val total = book.durationMs
    if (total <= 0) return null
    return (bookPositionMs(book, position) * 100 / total).coerceIn(0, 100).toInt()
}

/** The index into [chapters] that [position] is in. */
fun chapterIndexAt(chapters: List<BookChapter>, position: BookPosition): Int {
    val found = chapters.indexOfLast { it.fileIndex == position.fileIndex && it.startMs <= position.positionMs }
    if (found >= 0) return found
    // Before the first mark of that file, or a file index past the end.
    val first = chapters.indexOfFirst { it.fileIndex >= position.fileIndex }
    return if (first >= 0) first else chapters.lastIndex
}

/** "Chapter 12 of 40, 14:32 left in chapter" (without the time when it isn't known). */
fun chapterSpotText(chapters: List<BookChapter>, position: BookPosition): String? {
    if (chapters.isEmpty()) return null
    val index = chapterIndexAt(chapters, position)
    val chapter = chapters[index]
    val spot = "Chapter ${index + 1} of ${chapters.size}"
    val end = chapter.endMs ?: return spot
    val left = (end - position.positionMs).coerceIn(0, end - chapter.startMs)
    return "$spot, ${formatTime(left)} left in chapter"
}

/**
 * The progress on a book's row: "Finished", "43%", "Started" (length unknown), or null
 * when it hasn't been started.
 */
fun progressText(book: Book, position: BookPosition?): String? = when {
    isFinished(book, position) -> "Finished"
    !isInProgress(book, position) -> null
    else -> percentListened(book, position!!)?.let { "$it%" } ?: "Started"
}

/** How much is left to hear, or null when the length isn't known or it's not started. */
fun timeLeftMs(book: Book, position: BookPosition?): Long? {
    if (position == null || book.durationMs <= 0) return null
    return (book.durationMs - bookPositionMs(book, position)).coerceAtLeast(0)
}

/** "12 hr 5 min", "48 min"; "1 min" at least. */
fun bookLengthText(ms: Long): String {
    val minutes = ((ms + 30_000) / 60_000).coerceAtLeast(1)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} hr ${minutes % 60} min"
}

/**
 * The place [absoluteMs] into the whole book: the file it falls in and the position inside
 * that file. Clamped to the book; files with no known length are skipped over.
 */
fun locate(book: Book, absoluteMs: Long): BookPosition {
    var left = absoluteMs.coerceAtLeast(0)
    book.files.forEachIndexed { i, file ->
        if (left < file.durationMs || i == book.files.lastIndex) {
            val within = if (file.durationMs > 0) left.coerceAtMost(file.durationMs) else left
            return BookPosition(fileIndex = i, positionMs = within)
        }
        left -= file.durationMs
    }
    return BookPosition()
}

/** Whether [position] is in the book's last 30 seconds (ignoring the finished flag). */
fun isNearEnd(book: Book, position: BookPosition): Boolean = isFinished(book, position.copy(finished = false))

/** Pauses shorter than this don't rewind (a seek or a skip pauses for a moment, too). */
const val REWIND_MIN_PAUSE_MS = 2_000L

/** From this long a pause on, the longer rewind applies. */
const val REWIND_LONG_PAUSE_MS = 10 * 60_000L

/**
 * How far to go back when a book plays again after a pause of [pausedForMs]: nothing for a
 * moment's pause, [shortSeconds] under 10 minutes, [longSeconds] after that.
 */
fun rewindMs(pausedForMs: Long, shortSeconds: Int, longSeconds: Int): Long = when {
    pausedForMs < REWIND_MIN_PAUSE_MS -> 0
    pausedForMs < REWIND_LONG_PAUSE_MS -> shortSeconds.coerceAtLeast(0) * 1000L
    else -> longSeconds.coerceAtLeast(0) * 1000L
}
