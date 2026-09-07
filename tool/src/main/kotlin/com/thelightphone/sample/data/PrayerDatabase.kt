package com.thelightphone.sample.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [Group::class, Person::class, Entry::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class PrayerDatabase : RoomDatabase() {
    abstract fun prayerDao(): PrayerDao
}
