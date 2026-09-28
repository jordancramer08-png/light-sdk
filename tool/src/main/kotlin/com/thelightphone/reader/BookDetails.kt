package com.thelightphone.reader

import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.ReadingPosition
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToLong

/**
 * What the Book Details screen shows, worked out in plain Kotlin so it can be unit-tested
 * on the PC (CLAUDE.md 9). No Android or Compose here.
 */

/** One "label   value" line on the details screen. */
data class DetailRow(val label: String, val value: String)

/** Reading speed used for "Reading time" and "Time left". */
const val WORDS_PER_MINUTE = 250

/**
 * The details rows, top to bottom. The title and author are drawn above them, so they
 * aren't rows. The series row is left out when the book isn't part of one.
 */
fun bookDetailRows(
    meta: BookMeta,
    position: ReadingPosition?,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<DetailRow> {
    val numbers = NumberFormat.getIntegerInstance(locale)
    return listOfNotNull(
        seriesText(meta)?.let { DetailRow("Series", it) },
        DetailRow("Chapters", numbers.format(meta.chapters.size)),
        DetailRow("Words", numbers.format(meta.words)),
        DetailRow("Reading time", readingTimeText(meta.words)),
        DetailRow("Time left", readingTimeText(wordsLeft(meta, position))),
        DetailRow("Progress", progressText(meta, position)),
        DetailRow("File", meta.source.fileName),
        DetailRow("File size", fileSizeText(meta.source.size)),
        DetailRow("Added", dateText(meta.source.modified, locale, zone)),
        DetailRow("Last read", position?.let { dateText(it.updatedAt, locale, zone) } ?: "Not yet"),
    )
}

/** "Cemetery of Forgotten Books, book 1", or just the name when there's no number. */
fun seriesText(meta: BookMeta): String? {
    val name = meta.series ?: return null
    val number = meta.seriesNumber ?: return name
    return "$name, book $number"
}

/**
 * Words from the saved place to the end: every later chapter, plus the unread share of
 * the current one (by characters, as progress is measured). No saved place = the whole book.
 */
fun wordsLeft(meta: BookMeta, position: ReadingPosition?): Long {
    if (position == null) return meta.words
    val later = meta.chapters.filter { it.index > position.chapterIndex }.sumOf { it.words.toLong() }
    val current = meta.chapters.firstOrNull { it.index == position.chapterIndex } ?: return later
    if (current.chars <= 0) return later
    val unreadShare = 1.0 - position.charOffset.coerceIn(0, current.chars).toDouble() / current.chars
    return later + (current.words * unreadShare).roundToLong()
}

/** "About 6 h 20 min", "About 45 min", "Under a minute" or "None" — at [WORDS_PER_MINUTE]. */
fun readingTimeText(words: Long): String {
    if (words <= 0) return "None"
    val minutes = (words.toDouble() / WORDS_PER_MINUTE).roundToLong()
    if (minutes < 1) return "Under a minute"
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0L -> "About $rest min"
        rest == 0L -> "About $hours h"
        else -> "About $hours h $rest min"
    }
}

/** "850 KB", "1.2 MB". */
fun fileSizeText(bytes: Long): String {
    val kb = bytes / 1024.0
    if (kb < 1024) return "${kb.roundToLong().coerceAtLeast(1)} KB"
    val mb = kb / 1024
    return if (mb < 10) "%.1f MB".format(Locale.ROOT, mb) else "${mb.roundToLong()} MB"
}

/** The phone's usual medium date, e.g. "Sep 28, 2026". */
fun dateText(epochMillis: Long, locale: Locale, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)
        .format(Instant.ofEpochMilli(epochMillis).atZone(zone))
