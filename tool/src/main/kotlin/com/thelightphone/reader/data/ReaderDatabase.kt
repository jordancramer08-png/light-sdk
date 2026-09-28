package com.thelightphone.reader.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ReadingPosition::class],
    version = 1,
    exportSchema = false,
)
abstract class ReaderDatabase : RoomDatabase() {
    abstract fun readingPositionDao(): ReadingPositionDao
}
