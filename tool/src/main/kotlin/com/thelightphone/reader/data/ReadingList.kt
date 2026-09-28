package com.thelightphone.reader.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One of Jordan's reading lists, e.g. "Summer" or "Re-read". Added in database version 2. */
@Entity(tableName = "reading_list")
data class ReadingList(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

/**
 * One book in one list. A book is known by its slug, the same key reading positions use.
 * [sortOrder] is the book's place in the list: new books go at the end, and moving a book
 * up or down swaps its [sortOrder] with its neighbour's.
 */
@Entity(tableName = "reading_list_book", primaryKeys = ["listId", "bookSlug"])
data class ReadingListBook(
    val listId: Long,
    val bookSlug: String,
    val sortOrder: Int,
    val addedAt: Long,
)
