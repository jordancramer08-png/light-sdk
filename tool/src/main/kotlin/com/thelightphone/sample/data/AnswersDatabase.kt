package com.thelightphone.sample.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [Answer::class],
    version = 1,
    exportSchema = false,
)
abstract class AnswersDatabase : RoomDatabase() {
    abstract fun answerDao(): AnswerDao
}
