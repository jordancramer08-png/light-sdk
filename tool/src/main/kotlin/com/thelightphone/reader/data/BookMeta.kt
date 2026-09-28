package com.thelightphone.reader.data

import kotlinx.serialization.Serializable

/**
 * One book as cached in `<filesDir>/library/<slug>/meta.json` (CLAUDE.md 6).
 * The first four fields are the September app's meta.json, unchanged.
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
)

/** `index` is 1-based and counts chapters after filtering; `file` is e.g. "001.txt". */
@Serializable
data class ChapterMeta(
    val index: Int,
    val title: String,
    val file: String,
    val chars: Int,
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
