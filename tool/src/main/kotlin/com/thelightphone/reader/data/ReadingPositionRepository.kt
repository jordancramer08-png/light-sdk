package com.thelightphone.reader.data

/**
 * The one place the rest of the app talks to for reading position. Saved
 * continuously while reading and flushed when leaving ReaderScreen, the same
 * autosave contract the Small Group app used for answers (CLAUDE.md 7).
 */
class ReadingPositionRepository private constructor(database: ReaderDatabase) {

    private val dao = database.readingPositionDao()

    fun get(bookSlug: String): ReadingPosition? = dao.get(bookSlug)

    fun getAll(): List<ReadingPosition> = dao.getAll()

    fun save(bookSlug: String, chapterIndex: Int, charOffset: Int, updatedAt: Long) {
        dao.upsert(ReadingPosition(bookSlug, chapterIndex, charOffset, updatedAt))
    }

    companion object {
        const val DATABASE_NAME = "reading_position.db"

        @Volatile
        private var instance: ReadingPositionRepository? = null

        fun getInstance(databaseProvider: () -> ReaderDatabase): ReadingPositionRepository {
            return instance ?: synchronized(this) {
                instance ?: ReadingPositionRepository(databaseProvider()).also { instance = it }
            }
        }
    }
}
