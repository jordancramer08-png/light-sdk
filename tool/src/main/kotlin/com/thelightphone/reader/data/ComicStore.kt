package com.thelightphone.reader.data

import com.thelightphone.reader.RemovalSummary
import com.thelightphone.reader.comics.ComicDetails
import com.thelightphone.reader.comics.ComicFolderItem
import com.thelightphone.reader.comics.ComicItemKind
import com.thelightphone.reader.comics.MAX_PAGE_BYTES
import com.thelightphone.reader.comics.PANEL_DETECTOR_VERSION
import com.thelightphone.reader.comics.comicDetails
import com.thelightphone.reader.comics.comicItemKind
import com.thelightphone.reader.comics.decodeNoteText
import com.thelightphone.reader.comics.detailsSidecarName
import com.thelightphone.reader.comics.folderItems
import com.thelightphone.reader.comics.readComicEntry
import com.thelightphone.reader.comics.readComicInfo
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

    /**
     * The last comic's panels.json, kept in memory so a long comic's file isn't read again for
     * every page. Only this ComicStore writes it while the comic is open. Guarded by [LOCK].
     */
    private var panelsInMemory: Pair<ComicMeta, ComicPanels>? = null

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
     * and saved. Null when the page can't be read. [readPage] gives the page's bytes (the
     * viewer passes its open CBZ). Looking takes a moment: call it off the main thread.
     */
    fun panels(
        meta: ComicMeta,
        page: String,
        readPage: () -> ByteArray? = { readComicEntry(fileOf(meta.path), page, MAX_PAGE_BYTES) },
    ): PagePanels? {
        cachedPanels(meta, page)?.let { return it }
        val found = try {
            readPage()?.let(findPanels)
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

    /**
     * What Comic Details shows: the comic's details file (`<name>.details.json` beside it),
     * then its ComicInfo.xml, then its file name, each value from the first that has it.
     * Reads the CBZ's list of files: call it off the main thread.
     */
    fun details(meta: ComicMeta): ComicDetails {
        val file = fileOf(meta.path)
        val sidecar = orNull { sidecarFile(meta.path).takeIf { it.isFile }?.let { decodeNoteText(it.readBytes()) } }
        val comicInfo = orNull { readComicInfo(file) }
        return comicDetails(sidecar, comicInfo, file.name, meta.pageCount)
    }

    // --- removing -----------------------------------------------------------------------

    /**
     * What removing [path] would take off: the comic or note there, or, for a folder, every
     * comic and note in it and the folders under it. The bytes count the files, the comics'
     * details files and their cache folders.
     */
    fun removal(path: String): RemovalSummary {
        val paths = removablePaths(path)
        val comics = paths.filter { comicItemKind(it.substringAfterLast('/'), false) == ComicItemKind.COMIC }
        val bytes = paths.sumOf { bytesUnder(fileOf(it)) } +
            comics.sumOf { bytesUnder(sidecarFile(it)) + bytesUnder(cacheFolder(it)) }
        return RemovalSummary(comics = comics.size, notes = paths.size - comics.size, bytes = bytes)
    }

    /**
     * Deletes the comic or note at [path], or every comic and note in the folder at [path]
     * and the folders under it, with each comic's details file and cache folder; then the
     * folders this left empty. Saved places, status and lists aren't touched (they are keyed
     * by path), so a comic sent again carries on. Other files stay, and so do their folders.
     * False when a comic or note couldn't be deleted.
     */
    fun remove(path: String): Boolean = synchronized(LOCK) {
        if (path.isEmpty()) return false // never the whole comics folder
        val items = removablePaths(path)
        for (item in items) {
            fileOf(item).delete()
            sidecarFile(item).delete()
            cacheFolder(item).deleteRecursively()
        }
        panelsInMemory = null
        removeEmptyFolders(fileOf(path))
        items.none { fileOf(it).exists() }
    }

    /** The comics and notes at or under [path], as paths. Hidden folders and `__MACOSX` are left alone. */
    private fun removablePaths(path: String): List<String> {
        if (path.isEmpty()) return emptyList() // never the whole comics folder
        val start = fileOf(path)
        if (start.isFile) return if (comicItemKind(start.name, false) != null) listOf(path) else emptyList()
        return start.walkTopDown()
            .onEnter { it == start || comicItemKind(it.name, true) != null }
            .filter { it.isFile && comicItemKind(it.name, false) != null }
            .map { it.relativeTo(comicsDir).invariantSeparatorsPath }
            .toList()
    }

    /** Deletes empty folders at and under [start] (deepest first), then its parents while they're empty. Never the comics folder. */
    private fun removeEmptyFolders(start: File) {
        if (start.isDirectory) start.walkBottomUp().filter { it.isDirectory }.forEach { it.delete() }
        var folder = start.parentFile
        while (folder != null && folder != comicsDir && folder.startsWith(comicsDir) && folder.delete()) {
            folder = folder.parentFile
        }
    }

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
        if (!isCurrent(meta)) return
        val saved = readPanels(meta) ?: ComicPanels()
        val updated = ComicPanels(pages = saved.pages + (page to found))
        val folder = cacheFolder(meta.path)
        val temp = File(folder, "$PANELS_FILE.tmp")
        temp.writeText(json.encodeToString(ComicPanels.serializer(), updated))
        Files.move(temp.toPath(), File(folder, PANELS_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING)
        panelsInMemory = meta to updated
    }

    /**
     * Null when panels.json is missing, unreadable, from another version of the detector, or
     * the comic changed. Read from the file once, then from memory. Call it holding [LOCK].
     */
    private fun readPanels(meta: ComicMeta): ComicPanels? {
        if (!isCurrent(meta)) return null
        panelsInMemory?.let { (forMeta, panels) -> if (forMeta == meta) return panels }
        val file = File(cacheFolder(meta.path), PANELS_FILE)
        if (!file.isFile) return null
        val panels = try {
            json.decodeFromString(ComicPanels.serializer(), file.readText())
        } catch (e: IllegalArgumentException) { // includes SerializationException
            return null
        }
        if (panels.version != PANEL_DETECTOR_VERSION) return null
        panelsInMemory = meta to panels
        return panels
    }

    /**
     * True while [meta] still describes the comic on disk and its cache folder is there:
     * the file's size and time are checked, not meta.json re-read (it lists every page).
     */
    private fun isCurrent(meta: ComicMeta): Boolean {
        val file = fileOf(meta.path)
        return file.isFile && stampOf(file) == meta.source && File(cacheFolder(meta.path), META_FILE).isFile
    }

    // --- files -----------------------------------------------------------------------

    private fun fileOf(path: String): File = if (path.isEmpty()) comicsDir else File(comicsDir, path)

    private fun cacheFolder(path: String): File = File(cacheDir, cacheId(path))

    /** The details file beside a comic: "Name.cbz" -> "Name.details.json". */
    private fun sidecarFile(path: String): File = File(fileOf(path).parentFile, detailsSidecarName(fileOf(path).name))

    /** A file that can't be read gives null, not a crash. */
    private fun <T> orNull(read: () -> T?): T? = try {
        read()
    } catch (e: Exception) {
        null
    }

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
