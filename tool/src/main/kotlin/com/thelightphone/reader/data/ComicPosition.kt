package com.thelightphone.reader.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Where Jordan stopped in one comic. Added in database version 4. [slug] is the comic's key,
 * "comic:" + its path in the comics folder (`comicSlug`). [page] is 1-based; [panel] is 0 for
 * the whole page, else the panel on it (1, 2, …; used by the viewer).
 */
@Entity(tableName = "comic_position")
data class ComicPosition(
    @PrimaryKey val slug: String,
    val page: Int,
    val panel: Int,
    val updatedAt: Long,
)
