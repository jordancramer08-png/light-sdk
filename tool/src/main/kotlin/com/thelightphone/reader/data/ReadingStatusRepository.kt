package com.thelightphone.reader.data

import com.thelightphone.reader.ReadingStatus
import com.thelightphone.reader.statusAfterOpening

/**
 * The one place the rest of the app talks to for reading status. Every call touches the
 * database, so call it through [DatabaseQueue].
 */
class ReadingStatusRepository private constructor(database: ReaderDatabase) {

    private val dao = database.bookStatusDao()

    fun get(bookSlug: String): ReadingStatus = ReadingStatus.fromSavedName(dao.get(bookSlug)?.status)

    /** Every book that has a status saved, by slug. Books missing here are Want to Read. */
    fun getAll(): Map<String, ReadingStatus> =
        dao.getAll().associate { it.bookSlug to ReadingStatus.fromSavedName(it.status) }

    fun set(bookSlug: String, status: ReadingStatus) {
        dao.upsert(BookStatus(bookSlug, status.name, System.currentTimeMillis()))
    }

    /** The book was opened: Want to Read becomes Reading. Anything else stays. */
    fun markOpened(bookSlug: String) {
        statusAfterOpening(get(bookSlug))?.let { set(bookSlug, it) }
    }

    /** The last page was reached. */
    fun markFinished(bookSlug: String) {
        if (get(bookSlug) != ReadingStatus.FINISHED) set(bookSlug, ReadingStatus.FINISHED)
    }

    companion object {
        @Volatile
        private var instance: ReadingStatusRepository? = null

        fun getInstance(databaseProvider: () -> ReaderDatabase): ReadingStatusRepository {
            return instance ?: synchronized(this) {
                instance ?: ReadingStatusRepository(databaseProvider()).also { instance = it }
            }
        }
    }
}
