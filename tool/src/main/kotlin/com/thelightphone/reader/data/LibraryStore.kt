package com.thelightphone.reader.data

import com.thelightphone.reader.epub.Book
import com.thelightphone.reader.epub.DrmProtectedException
import com.thelightphone.reader.epub.EpubParser
import com.thelightphone.reader.epub.PARSER_VERSION
import com.thelightphone.reader.epub.countWords
import com.thelightphone.reader.epub.slugify
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The books on the phone (CLAUDE.md 5, 6).
 *
 * EPUBs sit in `<filesDir>/shared/books/`. Each one is parsed once and cached as
 * `<filesDir>/library/<slug>/meta.json` plus `001.txt`, `002.txt`, … Later opens
 * read the cache. A book is parsed again when its file size or modified time
 * changes, or when [PARSER_VERSION] is bumped. Cache folders whose EPUB is gone
 * are deleted.
 *
 * Everything here reads or writes files (and may parse EPUBs), so call it from
 * `Dispatchers.IO`. Methods are synchronized, so only one book is parsed at a time.
 */
class LibraryStore(
    private val booksDir: File,
    private val libraryDir: File,
    private val parse: (File) -> Book = EpubParser::parse,
    private val parserVersion: Int = PARSER_VERSION,
) {
    constructor(filesDir: File) : this(File(filesDir, "shared/books"), File(filesDir, "library"))

    private val json = Json { ignoreUnknownKeys = true }

    /** How many EPUBs are new or changed — for "Preparing N books…". Doesn't parse anything. */
    @Synchronized
    fun countBooksToPrepare(): Int {
        val cachedStamps = cacheFolders().mapNotNull { readMeta(it)?.source }.toSet()
        return epubFiles().count { stampOf(it) !in cachedStamps }
    }

    /**
     * Brings the cache in line with the EPUB folder and returns every book, in
     * file-name order. [onPreparing] is called before each parse with how many
     * books are still left to parse.
     */
    @Synchronized
    fun refresh(onPreparing: (remaining: Int) -> Unit = {}): List<BookMeta> {
        val files = epubFiles()
        val booksByFileName = keepFreshCacheFolders(files)

        val toParse = files.filter { it.name !in booksByFileName }
        toParse.forEachIndexed { i, file ->
            onPreparing(toParse.size - i)
            val slugsInUse = booksByFileName.values.map { it.slug }.toSet()
            booksByFileName[file.name] = prepare(file, slugsInUse)
        }
        return files.mapNotNull { booksByFileName[it.name] }
    }

    /** The cached book with this slug, or null if there isn't one. */
    @Synchronized
    fun book(slug: String): BookMeta? = readMeta(File(libraryDir, slug))

    /** One chapter's text, or null if its cache file is missing. */
    @Synchronized
    fun chapterText(book: BookMeta, chapter: ChapterMeta): String? {
        val file = File(File(libraryDir, book.slug), chapter.file)
        return if (file.isFile) file.readText() else null
    }

    // --- cache rules ----------------------------------------------------------

    /**
     * Keeps each cache folder that is complete and still matches its EPUB, and
     * deletes the rest: EPUB gone (orphan), EPUB changed (stale), or no readable
     * meta.json (half-written — the app was closed mid-parse).
     * Returns the kept books by EPUB file name.
     */
    private fun keepFreshCacheFolders(files: List<File>): MutableMap<String, BookMeta> {
        val currentStamps = files.map { stampOf(it) }.toSet()
        val kept = mutableMapOf<String, BookMeta>()
        for (folder in cacheFolders()) {
            val meta = readMeta(folder)
            val isFresh = meta != null && meta.source in currentStamps && meta.source.fileName !in kept
            if (isFresh) kept[meta.source.fileName] = meta else folder.deleteRecursively()
        }
        return kept
    }

    /** Parses one EPUB and writes its cache folder. meta.json is written last. */
    private fun prepare(file: File, slugsInUse: Set<String>): BookMeta {
        val stamp = stampOf(file)
        val book = try {
            parse(file)
        } catch (e: DrmProtectedException) {
            return saveUnreadable(file, stamp, BookProblem.DRM, slugsInUse)
        } catch (e: Exception) {
            return saveUnreadable(file, stamp, BookProblem.UNREADABLE, slugsInUse)
        }

        val slug = uniqueSlug(slugify(book.title), slugsInUse)
        val folder = emptyFolder(slug)
        val chapters = book.chapters.mapIndexed { i, chapter ->
            val fileName = chapterFileName(i + 1)
            File(folder, fileName).writeText(chapter.text)
            ChapterMeta(
                index = i + 1,
                title = chapter.title,
                file = fileName,
                chars = chapter.text.length,
                words = countWords(chapter.text),
            )
        }
        val meta = BookMeta(slug, book.title, book.author, chapters, stamp, series = book.series, seriesNumber = book.seriesNumber)
        return writeMeta(meta)
    }

    /** A book we can't open is still cached, so it isn't re-parsed on every launch. */
    private fun saveUnreadable(file: File, stamp: SourceStamp, problem: BookProblem, slugsInUse: Set<String>): BookMeta {
        val title = file.nameWithoutExtension
        val slug = uniqueSlug(slugify(title), slugsInUse)
        emptyFolder(slug)
        return writeMeta(BookMeta(slug, title, "Unknown", emptyList(), stamp, problem))
    }

    // --- files ------------------------------------------------------------------

    /** Only `*.epub` files count; the September app's leftover folders are ignored. */
    private fun epubFiles(): List<File> =
        booksDir.listFiles { f -> f.isFile && f.extension.equals("epub", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name }

    private fun cacheFolders(): List<File> =
        libraryDir.listFiles { f -> f.isDirectory }.orEmpty().toList()

    private fun stampOf(file: File) = SourceStamp(file.name, file.length(), file.lastModified(), parserVersion)

    /** Null when meta.json is missing, corrupt, or belongs to a different folder. */
    private fun readMeta(folder: File): BookMeta? {
        val file = File(folder, META_FILE)
        if (!file.isFile) return null
        val meta = try {
            json.decodeFromString(BookMeta.serializer(), file.readText())
        } catch (e: IllegalArgumentException) { // includes SerializationException
            return null
        }
        return meta.takeIf { it.slug == folder.name }
    }

    private fun writeMeta(meta: BookMeta): BookMeta {
        File(File(libraryDir, meta.slug), META_FILE).writeText(json.encodeToString(BookMeta.serializer(), meta))
        return meta
    }

    private fun emptyFolder(slug: String): File {
        val folder = File(libraryDir, slug)
        folder.deleteRecursively()
        folder.mkdirs()
        return folder
    }

    companion object {
        private const val META_FILE = "meta.json"

        /** "001.txt", "002.txt", … */
        fun chapterFileName(index: Int): String = index.toString().padStart(3, '0') + ".txt"

        /** `slug`, or `slug-2`, `slug-3`, … if that's already taken by another book. */
        fun uniqueSlug(slug: String, slugsInUse: Set<String>): String {
            if (slug !in slugsInUse) return slug
            var n = 2
            while ("$slug-$n" in slugsInUse) n++
            return "$slug-$n"
        }
    }
}
