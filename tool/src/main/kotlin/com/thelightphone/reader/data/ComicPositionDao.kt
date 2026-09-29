package com.thelightphone.reader.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ComicPositionDao {

    @Upsert
    fun upsert(position: ComicPosition)

    @Query("SELECT * FROM comic_position WHERE slug = :slug")
    fun get(slug: String): ComicPosition?

    /** For the comics list's "Page 12 of 30" on every comic at once. */
    @Query("SELECT * FROM comic_position")
    fun getAll(): List<ComicPosition>
}
