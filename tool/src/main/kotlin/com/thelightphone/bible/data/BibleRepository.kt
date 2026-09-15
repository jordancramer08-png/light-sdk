package com.thelightphone.bible.data

import com.thelightphone.sdk.LightFileShare
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Reads converted scripture text from the shared directory the converter's output is
 * copied into on-device (`lightContext.fileShare`, root `bible/<translation>/`). Never
 * parses EPUB and never alters verse text (CLAUDE.md 6, 10).
 */
class BibleRepository(private val fileShare: LightFileShare) {

    private val json = Json { ignoreUnknownKeys = true }
    private val manifestCache = mutableMapOf<String, BibleManifest?>()

    fun loadManifest(translation: String): BibleManifest? {
        manifestCache[translation]?.let { return it }
        val text = fileShare.read("$ROOT_DIR/$translation/$MANIFEST_FILE") { it.readText() } ?: return null
        val manifest = try {
            json.decodeFromString(BibleManifest.serializer(), text)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        manifestCache[translation] = manifest
        return manifest
    }

    /**
     * Finds the book in [manifest] matching [bookName] under any spelling [resolver] knows,
     * e.g. a reading-plan passage's "Song of Songs" against a manifest keyed "Song of Solomon".
     */
    fun findBook(manifest: BibleManifest, resolver: BookNameResolver, bookName: String): BibleManifestBook? {
        val canonical = resolver.resolve(bookName) ?: return null
        return manifest.books.entries
            .firstOrNull { (name, _) -> resolver.resolve(name) == canonical }
            ?.value
    }

    fun loadChapter(translation: String, bookSlug: String, chapter: Int): List<Verse>? {
        val text = fileShare.read("$ROOT_DIR/$translation/$bookSlug/$chapter.txt") { it.readText() } ?: return null
        return parseVerses(text)
    }

    /** Verses [startVerse]..[endVerse] inclusive, or the whole chapter if either bound is null. */
    fun loadVerseRange(
        translation: String,
        bookSlug: String,
        chapter: Int,
        startVerse: Int? = null,
        endVerse: Int? = null,
    ): List<Verse>? {
        val verses = loadChapter(translation, bookSlug, chapter) ?: return null
        return filterVerseRange(verses, startVerse, endVerse)
    }

    companion object {
        private const val ROOT_DIR = "bible"
        private const val MANIFEST_FILE = "manifest.json"
    }
}
