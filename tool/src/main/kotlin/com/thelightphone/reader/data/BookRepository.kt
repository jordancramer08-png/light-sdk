package com.thelightphone.reader.data

import com.thelightphone.sdk.LightFileShare
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Reads books from the shared directory the converter's output is copied
 * into on-device (`lightContext.fileShare`, root `books/<slug>/`). Never
 * parses EPUB and never alters chapter text - returns exactly what the
 * converter wrote (CLAUDE.md 5, 10).
 */
class BookRepository(private val fileShare: LightFileShare) {

    private val json = Json { ignoreUnknownKeys = true }

    /** A malformed or unreadable book folder is skipped rather than failing the whole library. */
    fun listBooks(): List<BookMeta> =
        fileShare.list(BOOKS_DIR).mapNotNull { slug -> loadMeta(slug) }

    fun loadMeta(slug: String): BookMeta? {
        val text = fileShare.read("$BOOKS_DIR/$slug/$META_FILE") { it.readText() } ?: return null
        return try {
            json.decodeFromString(BookMeta.serializer(), text)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun loadChapterText(slug: String, chapterFile: String): String? =
        fileShare.read("$BOOKS_DIR/$slug/$chapterFile") { it.readText() }

    companion object {
        private const val BOOKS_DIR = "books"
        private const val META_FILE = "meta.json"
    }
}
