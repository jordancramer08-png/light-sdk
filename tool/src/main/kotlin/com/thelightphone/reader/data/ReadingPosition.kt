package com.thelightphone.reader.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Where Jordan stopped in one book: a chapter index plus a character offset
 * into that chapter's text, not a page number - pagination is recomputed
 * from screen/font metrics each time, so a page number wouldn't survive a
 * font-size change (CLAUDE.md 7).
 *
 * `chapterIndex` is 1-based: it matches [ChapterMeta.index], as it did in the
 * September app. Do not change this class — its shape is the database schema,
 * and keeping it identical is what lets old saved places survive the upgrade.
 */
@Entity(tableName = "reading_position")
data class ReadingPosition(
    @PrimaryKey val bookSlug: String,
    val chapterIndex: Int,
    val charOffset: Int,
    val updatedAt: Long,
)
