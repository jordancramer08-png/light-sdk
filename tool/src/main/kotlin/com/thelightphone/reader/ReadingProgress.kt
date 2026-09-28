package com.thelightphone.reader

import com.thelightphone.reader.data.ChapterMeta
import kotlin.math.roundToLong

/**
 * The progress line under the page ("Ch 5 of 63 · 34% · 12 min left in chapter") and the
 * reading speed behind its time estimate. Plain Kotlin so it can be unit-tested on the PC.
 */

/** The speed the estimate starts from, before any pages have been timed. */
const val DEFAULT_CHARS_PER_MINUTE = 1400f

/** A page turned faster than this was skipped or skimmed, not read. */
const val MIN_PAGE_TIME_MS = 2_000L

/** A page left longer than this was put down, not read. */
const val MAX_PAGE_TIME_MS = 5 * 60_000L

/** How much one timed page moves the average: about the last 20 pages count. */
const val SPEED_WEIGHT = 0.1f

/**
 * Characters a minute for one page: [chars] on it, read in [elapsedMs]. Null when the time
 * is under 2 seconds or over 5 minutes, so it doesn't count.
 */
fun pageSpeed(chars: Int, elapsedMs: Long): Float? {
    if (chars <= 0 || elapsedMs < MIN_PAGE_TIME_MS || elapsedMs > MAX_PAGE_TIME_MS) return null
    return chars * 60_000f / elapsedMs
}

/** The rolling average with one more timed page in it: each page moves it a tenth of the way. */
fun updatedSpeed(average: Float, pageSpeed: Float): Float = average + (pageSpeed - average) * SPEED_WEIGHT

/** Whole minutes to read [chars] at [charsPerMinute]; at least 1 while anything is left. */
fun minutesLeft(chars: Long, charsPerMinute: Float): Long {
    if (chars <= 0) return 0
    val speed = if (charsPerMinute > 0) charsPerMinute else DEFAULT_CHARS_PER_MINUTE
    return (chars / speed).roundToLong().coerceAtLeast(1)
}

/** "12 min left in chapter". */
fun chapterTimeLeftText(minutes: Long): String = "$minutes min left in chapter"

/** "3 hr 20 min left in book", "3 hr left in book" or "45 min left in book". */
fun bookTimeLeftText(minutes: Long): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0L -> "$rest min left in book"
        rest == 0L -> "$hours hr left in book"
        else -> "$hours hr $rest min left in book"
    }
}

/** What the time part of the progress line counts down; tapping the line switches it. */
enum class TimeLeftMode {
    CHAPTER,
    BOOK;

    val other: TimeLeftMode get() = if (this == CHAPTER) BOOK else CHAPTER
}

/** Where the reader is, in the numbers the progress line needs. */
data class ReadingProgress(
    val chapterNumber: Int,
    val chapterCount: Int,
    val percent: Int,
    val charsLeftInChapter: Long,
    val charsLeftInBook: Long,
)

/**
 * The progress at [pageStart] (the page's first character) of the chapter [chapterIndex],
 * whose text on the page is [chapterLength] characters long. The percent is the library's
 * "NN% read" ([percentRead]); what's left in the book adds every later chapter.
 */
fun readingProgress(chapters: List<ChapterMeta>, chapterIndex: Int, pageStart: Int, chapterLength: Int): ReadingProgress {
    val leftInChapter = (chapterLength - pageStart).coerceAtLeast(0).toLong()
    val later = chapters.filter { it.index > chapterIndex }.sumOf { it.chars.toLong() }
    return ReadingProgress(
        chapterNumber = chapters.indexOfFirst { it.index == chapterIndex } + 1,
        chapterCount = chapters.size,
        percent = percentRead(chapters, chapterIndex, pageStart) ?: 0,
        charsLeftInChapter = leftInChapter,
        charsLeftInBook = leftInChapter + later,
    )
}

/** "Ch 5 of 63 · 34% · 12 min left in chapter" (or "… · 3 hr 20 min left in book"). */
fun progressLineText(progress: ReadingProgress, charsPerMinute: Float, mode: TimeLeftMode): String {
    val timeLeft = when (mode) {
        TimeLeftMode.CHAPTER -> chapterTimeLeftText(minutesLeft(progress.charsLeftInChapter, charsPerMinute))
        TimeLeftMode.BOOK -> bookTimeLeftText(minutesLeft(progress.charsLeftInBook, charsPerMinute))
    }
    return "Ch ${progress.chapterNumber} of ${progress.chapterCount} · ${progress.percent}% · $timeLeft"
}
