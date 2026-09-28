package com.thelightphone.reader.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One book's reading status (Want to Read, Reading, Finished). Added in database version 3.
 * A book is known by its slug, like saved places. [status] is the `ReadingStatus` name,
 * kept as text so an unknown value can fall back to Want to Read instead of crashing.
 * A book with no row here is Want to Read.
 */
@Entity(tableName = "reading_status")
data class BookStatus(
    @PrimaryKey val bookSlug: String,
    val status: String,
    val updatedAt: Long,
)
