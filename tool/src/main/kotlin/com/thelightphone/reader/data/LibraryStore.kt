package com.thelightphone.reader.data

import com.thelightphone.reader.epub.Book
import com.thelightphone.reader.epub.DrmProtectedException
import com.thelightphone.reader.epub.EpubParser
import com.thelightphone.reader.epub.PARSER_VERSION
import com.thelightphone.reader.epub.StyleRange
import com.thelightphone.reader.epub.countWords
import com.thelightphone.reader.epub.slugify
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The books on the phone (CLAUDE.md 5, 6).
 *
 * EPUBs sit in `<filesDir>/shared/books/`. Each one is parsed once and cached as
 * `<filesDir>/library/<slug>/meta.json` plus `001.txt`, `002.txt`, … (plain text) and,
 * for chapters with any italic, bold, quotes or scene breaks, `001.styles.json`, …, and when
 * the book has a cover, `cover-small.png` and `cover-large.png` ([CoverSize]). Later opens
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
    /** Writes a cover's two PNGs into a folder; false if the bytes aren't a readable picture. */
    private val saveCover: (ByteArray, File) -> Boolean = CoverImages::save,
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

    /** One chapter's italic, bold, quotes and scene breaks; none if it has no styles file (or it's unreadable). */
    @Synchronized
    fun chapterStyles(book: BookMeta, chapter: ChapterMeta): List<StyleRange> {
        val file = File(File(libraryDir, book.slug), stylesFileName(chapter.file))
        if (!file.isFile) return emptyList()
        return try {
            json.decodeFromString(STYLES_SERIALIZER, file.readText())
        } catch (e: IllegalArgumentException) { // includes SerializationException
            emptyList()
        }
    }

    /**
     * Where this book's cover picture of [size] is kept. The file is missing when the book
     * has no cover (or it couldn't be read). Only a path: nothing is read here.
     */
    fun coverFile(book: BookMeta, size: CoverSize): File = File(File(libraryDir, book.slug), size.fileName)

    /** The bytes removing [book] frees: its EPUB and its cache folder. */
    @Synchronized
    fun removalBytes(book: BookMeta): Long = bytesUnder(epubFile(book)) + bytesUnder(File(libraryDir, book.slug))

    /**
     * Deletes the book's EPUB and its cache folder. Its saved place, status and lists are
     * kept (they are keyed by slug), so sending it again opens where it was left.
     * False when the EPUB couldn't be deleted (nothing else is then touched).
     */
    @Synchronized
    fun remove(book: BookMeta): Boolean {
        val epub = epubFile(book)
        if (epub.exists() && !epub.delete()) return false
        File(libraryDir, book.slug).deleteRecursively()
        return true
    }

    /** False once the book's EPUB is gone from the phone. */
    fun isOnPhone(book: BookMeta): Boolean = epubFile(book).isFile

    private fun epubFile(book: BookMeta): File = File(booksDir, book.source.fileName)

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
            if (chapter.styles.isNotEmpty()) {
                File(folder, stylesFileName(fileName)).writeText(json.encodeToString(STYLES_SERIALIZER, chapter.styles))
            }
            ChapterMeta(
                index = i + 1,
                title = chapter.title,
                file = fileName,
                chars = chapter.text.length,
                words = countWords(chapter.text),
                depth = chapter.depth,
                parents = chapter.parents,
            )
        }
        book.cover?.let { saveCoverQuietly(it, folder) }
        val meta = BookMeta(slug, book.title, book.author, chapters, stamp, series = book.series, seriesNumber = book.seriesNumber)
        return writeMeta(meta)
    }

    /** A cover that can't be read (or is too big to decode) just leaves the book without one. */
    private fun saveCoverQuietly(bytes: ByteArray, folder: File) {
        val saved = try {
            saveCover(bytes, folder)
        } catch (e: Exception) {
            false
        } catch (e: OutOfMemoryError) {
            false
        }
        // Never leave one picture without the other (a half-written cover).
        if (!saved) CoverSize.entries.forEach { File(folder, it.fileName).delete() }
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
        private val STYLES_SERIALIZER = ListSerializer(StyleRange.serializer())

        /** "001.txt", "002.txt", … */
        fun chapterFileName(index: Int): String = index.toString().padStart(3, '0') + ".txt"

        /** "001.txt" -> "001.styles.json" */
        fun stylesFileName(chapterFile: String): String = chapterFile.substringBeforeLast('.') + ".styles.json"

        /** `slug`, or `slug-2`, `slug-3`, … if that's already taken by another book. */
        fun uniqueSlug(slug: String, slugsInUse: Set<String>): String {
            if (slug !in slugsInUse) return slug
            var n = 2
            while ("$slug-$n" in slugsInUse) n++
            return "$slug-$n"
        }
    }
}
