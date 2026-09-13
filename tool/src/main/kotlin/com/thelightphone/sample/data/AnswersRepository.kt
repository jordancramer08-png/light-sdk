package com.thelightphone.sample.data

/**
 * The one place the rest of the app talks to for saved answers - one row per
 * item id, whether that's a question or a lesson's Notes field. Every method
 * is blocking - call it from a background coroutine (`Dispatchers.IO`), the
 * same way [PrayerRepository] does.
 */
class AnswersRepository private constructor(database: AnswersDatabase) {

    private val dao = database.answerDao()

    fun getText(id: String): String? = dao.getText(id)

    /** Autosave: overwrites any existing answer for [id]. */
    fun save(id: String, text: String, updatedAt: Long) {
        dao.upsert(Answer(id = id, text = text, updatedAt = updatedAt))
    }

    /** How many of [itemIds] have a non-blank answer, e.g. for a lesson's "7/12". */
    fun countAnswered(itemIds: List<String>): Int =
        if (itemIds.isEmpty()) 0 else dao.countAnswered(itemIds)

    companion object {
        const val DATABASE_NAME = "answers.db"

        @Volatile
        private var instance: AnswersRepository? = null

        fun getInstance(databaseProvider: () -> AnswersDatabase): AnswersRepository {
            return instance ?: synchronized(this) {
                instance ?: AnswersRepository(databaseProvider()).also { instance = it }
            }
        }
    }
}
