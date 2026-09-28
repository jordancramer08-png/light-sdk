package com.thelightphone.reader.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface BookStatusDao {

    @Upsert
    fun upsert(status: BookStatus)

    @Query("SELECT * FROM reading_status WHERE bookSlug = :bookSlug")
    fun get(bookSlug: String): BookStatus?

    /** For the library's Show filter and "Finished" rows, every book at once. */
    @Query("SELECT * FROM reading_status")
    fun getAll(): List<BookStatus>
}
