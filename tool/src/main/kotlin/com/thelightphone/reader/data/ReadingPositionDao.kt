package com.thelightphone.reader.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ReadingPositionDao {

    @Upsert
    fun upsert(position: ReadingPosition)

    @Query("SELECT * FROM reading_position WHERE bookSlug = :bookSlug")
    fun get(bookSlug: String): ReadingPosition?

    /** For LibraryScreen's "how far through it" row on every book at once. */
    @Query("SELECT * FROM reading_position")
    fun getAll(): List<ReadingPosition>
}
