package com.thelightphone.reader.data

/**
 * The one place the rest of the app talks to for reading lists. Every call touches the
 * database, so call it from `Dispatchers.IO`.
 */
class ReadingListRepository private constructor(database: ReaderDatabase) {

    private val dao = database.readingListDao()

    fun lists(): List<ReadingList> = dao.lists()

    fun list(listId: Long): ReadingList? = dao.list(listId)

    fun create(name: String): Long = dao.insertList(ReadingList(name = name, createdAt = now()))

    fun rename(listId: Long, name: String) = dao.rename(listId, name)

    fun delete(listId: Long) = dao.deleteList(listId)

    /** The list's book slugs, in the order they were added (or rearranged to). */
    fun bookSlugs(listId: Long): List<String> = dao.bookSlugs(listId)

    fun listIdsContaining(bookSlug: String): Set<Long> = dao.listIdsContaining(bookSlug).toSet()

    fun addBook(listId: Long, bookSlug: String) = dao.addBook(listId, bookSlug, now())

    fun removeBook(listId: Long, bookSlug: String) = dao.removeBook(listId, bookSlug)

    fun swapBooks(listId: Long, firstSlug: String, secondSlug: String) =
        dao.swapBooks(listId, firstSlug, secondSlug)

    private fun now() = System.currentTimeMillis()

    companion object {
        @Volatile
        private var instance: ReadingListRepository? = null

        fun getInstance(databaseProvider: () -> ReaderDatabase): ReadingListRepository {
            return instance ?: synchronized(this) {
                instance ?: ReadingListRepository(databaseProvider()).also { instance = it }
            }
        }
    }
}
