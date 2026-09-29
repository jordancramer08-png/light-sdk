package com.thelightphone.reader.data

import com.thelightphone.reader.comics.ComicFolderItem
import com.thelightphone.reader.comics.ComicItemKind
import com.thelightphone.reader.comics.MAX_PAGE_BYTES
import com.thelightphone.reader.comics.PANEL_DETECTOR_VERSION
import com.thelightphone.reader.comics.comicItemKind
import com.thelightphone.reader.comics.decodeNoteText
import com.thelightphone.reader.comics.folderItems
import com.thelightphone.reader.comics.readComicEntry
import com.thelightphone.reader.comics.readComicPages
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The comics on the phone (CLAUDE.md 12).
 *
 * CBZs and note files sit in `<filesDir>/shared/comics/`, in the same folders as on the PC.
 * Each comic is read once — its page list, and its first page as a small cover — and cached
 * in `<filesDir>/comic-library/<id>/` (`meta.json`, `cover-small.png`); `<id>` comes from the
 * comic's path. A page's panels are looked for the first time they're asked for and kept in
 * the same folder (`panels.json`). A comic is read again when its size or modified time changes, or when
 * [COMIC_CACHE_VERSION] is bumped. [removeOrphans] deletes cache folders whose comic is gone.
 *
 * Everything here reads files, so call it from `Dispatchers.IO`. Preparing and clearing
 * orphans share one lock across every ComicStore (each screen has its own), so only one
 * comic is read at a time and a folder being written is never swept away.
 */
class ComicStore(
    private val comicsDir: File,
    private val cacheDir: File,
    /** Writes the small cover PNG into a folder; false if the bytes aren't a readable picture. */
    private val saveCover: (ByteArray, File) -> Boolean = CoverImages::saveSmall,
    /** Finds a page's panels from its bytes; null if they aren't a readable picture. */
    private val findPanels: (ByteArray) -> PagePanels? = ComicPageImages::findPanels,
) {
    constructor(filesDir: File) : this(File(filesDir, "shared/comics"), File(filesDir, "comic-library"))

    private val json = Json { ignoreUnknownKeys = true }

    /** One folder's subfolders, comics and notes, in the order shown. [folder] "" is the top. */
    fun list(folder: String): List<ComicFolderItem> {
        val children = fileOf(folder).listFiles().orEmpty().map { it.name to it.isDirectory }
        return folderItems(folder, children)
    }

    /** How many subfolders and comics sit directly in [folder] (for its row's "3 folders · 12 comics"). */
    fun folderCounts(folder: String): Pair<Int, Int> {
        val kinds = fileOf(folder).listFiles().orEmpty().mapNotNull { comicItemKind(it.name, it.isDirectory) }
        return kinds.count { it == ComicItemKind.FOLDER } to kinds.count { it == ComicItemKind.COMIC }
    }

    fun exists(path: String): Boolean = fileOf(path).isFile

    /** The CBZ itself, for the viewer to read pages from. */
    fun comicFile(path: String): File = fileOf(path)

    /** The comic's cached details if they still match its file; null when it must be [prepared]. */
    fun cached(path: String): ComicMeta? {
        val file = fileOf(path)
        if (!file.isFile) return null
        return readMeta(cacheFolder(path))?.takeIf { it.path == path && it.source == stampOf(file) }
    }

    /** The comic's details, reading the CBZ first if they aren't cached. Null when the file is gone. */
    fun prepared(path: String): ComicMeta? = synchronized(LOCK) { cached(path) ?: prepare(path) }

    /** Where the comic's small cover is kept. Missing when it has none. Only a path: nothing is read. */
    fun coverFile(meta: ComicMeta): File = File(cacheFolder(meta.path), CoverSize.SMALL.fileName)

    /** A note file's text. */
    fun noteText(path: String): String = decodeNoteText(fileOf(path).readBytes())

    /**
     * One page's panels: from panels.json when the page was looked at before, else found now
     * and saved. Null when the page can't be read. Looking takes a moment: call it off the
     * main thread.
     */
    fun panels(meta: ComicMeta, page: String): PagePanels? {
        cachedPanels(meta, page)?.let { return it }
        val found = try {
            readComicEntry(fileOf(meta.path), page, MAX_PAGE_BYTES)?.let(findPanels)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null
        savePanels(meta, page, found)
        return found
    }

    /** The page's panels if they were looked for before (by this version of the detector). */
    fun cachedPanels(meta: ComicMeta, page: String): PagePanels? =
        synchronized(LOCK) { readPanels(meta)?.pages?.get(page) }

    /** Deletes cache folders whose comic is no longer on the phone (or that were left half-written). */
    fun removeOrphans() = synchronized(LOCK) {
        for (folder in cacheDir.listFiles { f -> f.isDirectory }.orEmpty()) {
            val meta = readMeta(folder)
            if (meta == null || !exists(meta.path) || folder.name != cacheId(meta.path)) folder.deleteRecursively()
        }
    }

    // --- reading a CBZ -------------------------------------------------------------

    /** Reads the page list and the cover; meta.json is written last. A CBZ that can't be read is cached as a problem. */
    private fun prepare(path: String): ComicMeta? {
        val file = fileOf(path)
        if (!file.isFile) return null
        val stamp = stampOf(file)
        val folder = cacheFolder(path)
        folder.deleteRecursively()
        folder.mkdirs()
        val pages = try {
            readComicPages(file)
        } catch (e: Exception) {
            emptyList()
        }
        pages.firstOrNull()?.let { saveCoverQuietly(file, it, folder) }
        val meta = ComicMeta(path, stamp, pages, problem = pages.isEmpty())
        File(folder, META_FILE).writeText(json.encodeToString(ComicMeta.serializer(), meta))
        return meta
    }

    /** A first page that can't be read (or is too big to decode) just leaves the comic without a cover. */
    private fun saveCoverQuietly(file: File, firstPage: String, folder: File) {
        val saved = try {
            val bytes = readComicEntry(file, firstPage)
            bytes != null && saveCover(bytes, folder)
        } catch (e: Exception) {
            false
        } catch (e: OutOfMemoryError) {
            false
        }
        if (!saved) File(folder, CoverSize.SMALL.fileName).delete()
    }

    /** Adds one page to panels.json, unless the comic changed (or its cache went) meanwhile. */
    private fun savePanels(meta: ComicMeta, page: String, found: PagePanels): Unit = synchronized(LOCK) {
        if (cached(meta.path) != meta) return
        val saved = readPanels(meta) ?: ComicPanels()
        val updated = ComicPanels(pages = saved.pages + (page to found))
        val folder = cacheFolder(meta.path)
        val temp = File(folder, "$PANELS_FILE.tmp")
        temp.writeText(json.encodeToString(ComicPanels.serializer(), updated))
        Files.move(temp.toPath(), File(folder, PANELS_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING)
        Unit
    }

    /** Null when panels.json is missing, unreadable, from another version of the detector, or the comic changed. */
    private fun readPanels(meta: ComicMeta): ComicPanels? {
        if (cached(meta.path) != meta) return null
        val file = File(cacheFolder(meta.path), PANELS_FILE)
        if (!file.isFile) return null
        val panels = try {
            json.decodeFromString(ComicPanels.serializer(), file.readText())
        } catch (e: IllegalArgumentException) { // includes SerializationException
            return null
        }
        return panels.takeIf { it.version == PANEL_DETECTOR_VERSION }
    }

    // --- files -----------------------------------------------------------------------

    private fun fileOf(path: String): File = if (path.isEmpty()) comicsDir else File(comicsDir, path)

    private fun cacheFolder(path: String): File = File(cacheDir, cacheId(path))

    private fun stampOf(file: File) = ComicStamp(file.length(), file.lastModified(), COMIC_CACHE_VERSION)

    /** Null when meta.json is missing or unreadable. */
    private fun readMeta(folder: File): ComicMeta? {
        val file = File(folder, META_FILE)
        if (!file.isFile) return null
        return try {
            json.decodeFromString(ComicMeta.serializer(), file.readText())
        } catch (e: IllegalArgumentException) { // includes SerializationException
            null
        }
    }

    companion object {
        private const val META_FILE = "meta.json"
        private const val PANELS_FILE = "panels.json"
        private val LOCK = Any()

        /** Bump when what's cached per comic changes, so every comic is read again. */
        const val COMIC_CACHE_VERSION = 1

        /** A short name for a comic's cache folder, the same every time for the same path. */
        fun cacheId(path: String): String =
            MessageDigest.getInstance("SHA-1").digest(path.toByteArray())
                .take(8)
                .joinToString("") { "%02x".format(it) }
    }
}
