package com.thelightphone.reader.data

/**
 * The one place the rest of the app talks to for saved places in comics. Every call touches
 * the database, so call it through [DatabaseQueue].
 */
class ComicPositionRepository private constructor(database: ReaderDatabase) {

    private val dao = database.comicPositionDao()

    fun get(slug: String): ComicPosition? = dao.get(slug)

    /** Every comic with a saved place, by slug. */
    fun getAll(): Map<String, ComicPosition> = dao.getAll().associateBy { it.slug }

    fun save(slug: String, page: Int, panel: Int) {
        dao.upsert(ComicPosition(slug, page, panel, System.currentTimeMillis()))
    }

    /** The comic was opened: its place is kept (page 1 the first time) and its time is now. */
    fun markOpened(slug: String) {
        val current = dao.get(slug)
        save(slug, current?.page ?: 1, current?.panel ?: 0)
    }

    companion object {
        @Volatile
        private var instance: ComicPositionRepository? = null

        fun getInstance(databaseProvider: () -> ReaderDatabase): ComicPositionRepository {
            return instance ?: synchronized(this) {
                instance ?: ComicPositionRepository(databaseProvider()).also { instance = it }
            }
        }
    }
}
