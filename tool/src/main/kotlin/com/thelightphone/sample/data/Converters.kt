package com.thelightphone.sample.data

import androidx.room.TypeConverter

/** Lets Room store the [EntryType] enum as plain text. */
class Converters {
    @TypeConverter
    fun entryTypeToString(type: EntryType): String = type.name

    @TypeConverter
    fun stringToEntryType(value: String): EntryType = EntryType.valueOf(value)
}
