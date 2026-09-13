package com.thelightphone.sample.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * All database access for saved answers. Every method is blocking; callers
 * run it off the main thread (see [AnswersRepository]).
 */
@Dao
interface AnswerDao {

    @Upsert
    fun upsert(answer: Answer)

    @Query("SELECT text FROM answers WHERE id = :id")
    fun getText(id: String): String?

    /** How many of [ids] have a non-blank answer - a lesson's "N/M" count. */
    @Query("SELECT COUNT(*) FROM answers WHERE id IN (:ids) AND trim(text) != ''")
    fun countAnswered(ids: List<String>): Int
}
