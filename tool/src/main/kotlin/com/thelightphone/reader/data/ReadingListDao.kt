package com.thelightphone.reader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class ReadingListDao {

    // --- lists ----------------------------------------------------------------

    @Query("SELECT * FROM reading_list ORDER BY name COLLATE NOCASE, id")
    abstract fun lists(): List<ReadingList>

    @Query("SELECT * FROM reading_list WHERE id = :listId")
    abstract fun list(listId: Long): ReadingList?

    @Insert
    abstract fun insertList(list: ReadingList): Long

    @Query("UPDATE reading_list SET name = :name WHERE id = :listId")
    abstract fun rename(listId: Long, name: String)

    @Query("DELETE FROM reading_list WHERE id = :listId")
    protected abstract fun deleteListRow(listId: Long)

    @Query("DELETE FROM reading_list_book WHERE listId = :listId")
    protected abstract fun deleteAllBooksOf(listId: Long)

    /** Deletes the list and its entries. The books themselves stay on the phone. */
    @Transaction
    open fun deleteList(listId: Long) {
        deleteAllBooksOf(listId)
        deleteListRow(listId)
    }

    // --- books in a list ------------------------------------------------------

    @Query("SELECT bookSlug FROM reading_list_book WHERE listId = :listId ORDER BY sortOrder, addedAt")
    abstract fun bookSlugs(listId: Long): List<String>

    @Query("SELECT listId FROM reading_list_book WHERE bookSlug = :bookSlug")
    abstract fun listIdsContaining(bookSlug: String): List<Long>

    @Query("SELECT * FROM reading_list_book WHERE listId = :listId AND bookSlug = :bookSlug")
    protected abstract fun entry(listId: Long, bookSlug: String): ReadingListBook?

    @Query("SELECT MAX(sortOrder) FROM reading_list_book WHERE listId = :listId")
    protected abstract fun lastSortOrder(listId: Long): Int?

    @Insert
    protected abstract fun insertEntry(entry: ReadingListBook)

    @Query("UPDATE reading_list_book SET sortOrder = :sortOrder WHERE listId = :listId AND bookSlug = :bookSlug")
    protected abstract fun setSortOrder(listId: Long, bookSlug: String, sortOrder: Int)

    @Query("DELETE FROM reading_list_book WHERE listId = :listId AND bookSlug = :bookSlug")
    abstract fun removeBook(listId: Long, bookSlug: String)

    /** Puts the book at the end of the list. Does nothing if it's already there. */
    @Transaction
    open fun addBook(listId: Long, bookSlug: String, now: Long) {
        if (entry(listId, bookSlug) != null) return
        val sortOrder = (lastSortOrder(listId) ?: -1) + 1
        insertEntry(ReadingListBook(listId, bookSlug, sortOrder, addedAt = now))
    }

    /** Trades the places of two books in the same list. */
    @Transaction
    open fun swapBooks(listId: Long, firstSlug: String, secondSlug: String) {
        val first = entry(listId, firstSlug) ?: return
        val second = entry(listId, secondSlug) ?: return
        setSortOrder(listId, firstSlug, second.sortOrder)
        setSortOrder(listId, secondSlug, first.sortOrder)
    }
}
