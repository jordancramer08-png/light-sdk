package com.thelightphone.reader.data

import kotlinx.serialization.Serializable

/**
 * One book as cached in `<filesDir>/library/<slug>/meta.json` (CLAUDE.md 6).
 * The first four fields are the September app's meta.json, unchanged.
 * Parser version 2 added the series and each chapter's word count; version 3 each
 * chapter's place in the contents (depth, parents).
 */
@Serializable
data class BookMeta(
    val slug: String,
    val title: String,
    val author: String,
    val chapters: List<ChapterMeta>,
    /** Which EPUB this came from, and what it looked like when parsed. */
    val source: SourceStamp,
    /** Set when the book can't be opened; it then has no chapters. */
    val problem: BookProblem? = null,
    /** The series and the book's place in it ("1", "2.5"), when the book is part of one. */
    val series: String? = null,
    val seriesNumber: String? = null,
) {
    /** Words in the whole book, counted once when it was prepared. */
    val words: Long get() = chapters.sumOf { it.words.toLong() }
}

/**
 * `index` is 1-based and counts chapters after filtering; `file` is e.g. "001.txt" (its
 * italic, bold, quotes and scene breaks, if any, are in "001.styles.json").
 * [parents] are the titles of the contents entries above this chapter, outermost first
 * (e.g. "BOOK 1: GARDENS OF THE MOON", "Book One: Pale"); [depth] is how many there are.
 */
@Serializable
data class ChapterMeta(
    val index: Int,
    val title: String,
    val file: String,
    val chars: Int,
    /** Words in this chapter, counted once when the book was prepared. */
    val words: Int = 0,
    val depth: Int = 0,
    val parents: List<String> = emptyList(),
)

/**
 * If any of these differ from the EPUB on disk now, the cache is stale and the
 * book is parsed again.
 */
@Serializable
data class SourceStamp(
    val fileName: String,
    val size: Long,
    val modified: Long,
    val parserVersion: Int,
)

@Serializable
enum class BookProblem {
    /** `META-INF/encryption.xml` present — shown as "Can't open (DRM)". */
    DRM,

    /** Not a valid EPUB, or no readable chapters. */
    UNREADABLE,
}
