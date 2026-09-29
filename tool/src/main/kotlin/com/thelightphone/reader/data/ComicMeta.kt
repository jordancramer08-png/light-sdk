package com.thelightphone.reader.data

import kotlinx.serialization.Serializable

/**
 * One comic as cached in `<filesDir>/comic-library/<id>/meta.json` (CLAUDE.md 12).
 * [path] is the comic's place in the comics folder; [pages] are its page pictures' names in
 * the zip, in reading order. [problem] is true when the CBZ can't be read or has no pages.
 */
@Serializable
data class ComicMeta(
    val path: String,
    val source: ComicStamp,
    val pages: List<String>,
    val problem: Boolean = false,
) {
    val pageCount: Int get() = pages.size
    val canOpen: Boolean get() = !problem
}

/** If any of these differ from the CBZ on disk now, the cache is stale and the comic is read again. */
@Serializable
data class ComicStamp(val size: Long, val modified: Long, val version: Int)
