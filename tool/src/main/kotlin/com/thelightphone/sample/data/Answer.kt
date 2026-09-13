package com.thelightphone.sample.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One free-text answer, keyed by item id (CLAUDE.md 5) - a question id like
 * "L17Q1", or a lesson's Notes field, "L17NOTES". Independent of the content
 * file and of item position: reinstalling the app with `adb install -r` must
 * never lose these.
 */
@Entity(tableName = "answers")
data class Answer(
    @PrimaryKey val id: String,
    val text: String,
    val updatedAt: Long,
)
