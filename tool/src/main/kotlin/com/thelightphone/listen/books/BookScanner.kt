package com.thelightphone.listen.books

import com.thelightphone.listen.music.AUDIO_EXTENSIONS
import com.thelightphone.listen.music.RawTags
import com.thelightphone.listen.music.titleFromFileName
import java.io.File
import java.util.zip.CRC32

/**
 * Finds the books under [booksDir] and reads them. A book is a folder with a book.json, or
 * (when the PC script didn't write one) a folder with audio files in it. Folders inside a
 * book aren't searched for more books. Hidden files and folders (starting with ".") are
 * skipped. A file's length is read with [readTags] only when the file is new or its size or
 * change time differ from the last index. Blocking: run off the main thread.
 */
class BookScanner(
    private val booksDir: File,
    private val lastSyncFile: File,
    private val readTags: (File) -> RawTags?,
) {

    /**
     * A cheap fingerprint of the audiobooks folder: the PC script's last-sync.txt plus every
     * folder's change time. If it matches the index's, nothing changed and no scan is needed.
     */
    fun stamp(): String {
        val sync = lastSyncFile.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        if (!booksDir.isDirectory) return "sync=$sync;no-books"
        val folders = booksDir.walkTopDown()
            .onEnter { it == booksDir || !it.name.startsWith(".") }
            .filter { it.isDirectory }
            .map { it.path + "=" + it.lastModified() }
            .sorted()
            .toList()
        val crc = CRC32().apply { update(folders.joinToString("\n").toByteArray()) }
        return "sync=$sync;folders=${folders.size};crc=${crc.value}"
    }

    /** Every book folder, in name order. */
    fun findBookFolders(): List<File> {
        val found = mutableListOf<File>()
        fun visit(dir: File) {
            val children = dir.listFiles()?.filterNot { it.name.startsWith(".") } ?: return
            if (dir != booksDir && children.any { it.isFile && (it.name == BOOK_JSON || isAudio(it)) }) {
                found += dir
                return
            }
            children.filter { it.isDirectory }.sortedWith(compareBy(NATURAL_ORDER) { it.name }).forEach(::visit)
        }
        if (booksDir.isDirectory) visit(booksDir)
        return found
    }

    /**
     * The books now on the phone. Books already in [previous] show at once through
     * [onProgress]; each book that needed files read is shown as soon as it's done, so a long
     * first scan fills the list as it goes.
     */
    fun scan(previous: List<Book>, onProgress: (List<Book>) -> Unit = {}): List<Book> {
        val known = previous.flatMap { book -> book.files.map { "${book.folder}/${it.path}" to it } }.toMap()
        val previousByFolder = previous.associateBy { it.folder }
        val folders = findBookFolders()
        val books = LinkedHashMap<String, Book>()
        for (folder in folders) {
            val rel = relativeFolder(folder)
            previousByFolder[rel]?.let { books[rel] = it }
        }
        for (folder in folders) {
            val rel = relativeFolder(folder)
            val (book, readAny) = readBook(folder, known)
            if (book == null) books.remove(rel) else books[rel] = book
            if (readAny) onProgress(books.values.toList())
        }
        return books.values.toList()
    }

    private fun relativeFolder(folder: File) = folder.relativeTo(booksDir).invariantSeparatorsPath

    /**
     * One book from its folder, and whether any file had to be read. Null when the folder
     * holds no audio file that can be found. [known] holds the last index's files by
     * "folder/path".
     */
    fun readBook(folder: File, known: Map<String, BookFile> = emptyMap()): Pair<Book?, Boolean> {
        val rel = relativeFolder(folder)
        val json = File(folder, BOOK_JSON).takeIf { it.isFile }?.let { file ->
            try {
                parseBookJson(file.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                null
            }
        }

        // book.json's files that are on the phone, in its order; else every audio file here.
        val listed = json?.files.orEmpty()
            .filter { File(folder, it.name).isFile }
            .distinctBy { it.name }
        val entries = listed.ifEmpty {
            audioFilesIn(folder).map { BookJsonFile(name = it, label = null, chapters = emptyList()) }
        }
        if (entries.isEmpty()) return null to false

        var readAny = false
        val files = entries.map { entry ->
            val file = File(folder, entry.name)
            val size = file.length()
            val modified = file.lastModified()
            val old = known["$rel/${entry.name}"]
            val base = if (old != null && old.size == size && old.modified == modified) {
                old
            } else {
                readAny = true
                val tags = readTags(file)
                BookFile(
                    path = entry.name,
                    label = "",
                    size = size,
                    modified = modified,
                    durationMs = tags?.durationMs?.trim()?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
                    tagTitle = tags?.title.clean(),
                    tagArtist = tags?.albumArtist.clean() ?: tags?.artist.clean(),
                )
            }
            base.copy(label = entry.label.orEmpty(), chapters = entry.chapters)
        }
        return bookFrom(rel, json, withLabels(files), coverOf(folder, json)) to readAny
    }

    /** The cover picture named by book.json, when it's there, with its change time. */
    private fun coverOf(folder: File, json: BookJson?): Pair<String, Long>? {
        val name = json?.cover ?: return null
        val file = File(folder, name)
        return if (file.isFile) name to file.lastModified() else null
    }

    /** Audio files anywhere in [folder], as paths relative to it, in natural name order. */
    private fun audioFilesIn(folder: File): List<String> =
        folder.walkTopDown()
            .onEnter { it == folder || !it.name.startsWith(".") }
            .filter { it.isFile && !it.name.startsWith(".") && isAudio(it) }
            .map { it.relativeTo(folder).invariantSeparatorsPath }
            .sortedWith(NATURAL_ORDER)
            .toList()

    private fun isAudio(file: File) = file.extension.lowercase() in AUDIO_EXTENSIONS

    companion object {
        const val BOOK_JSON = "book.json"
    }
}

/**
 * Each file's label: book.json's, else the file's title tag, else its file name. When every
 * file has the same title tag (often the book's name), the file names are used instead.
 */
fun withLabels(files: List<BookFile>): List<BookFile> {
    val sameTagEverywhere = files.size > 1 && files.map { it.tagTitle }.distinct().size == 1
    return files.map { file ->
        if (file.label.isNotBlank()) return@map file
        val tag = file.tagTitle.takeUnless { sameTagEverywhere }
        file.copy(label = tag ?: titleFromFileName(file.path.substringAfterLast('/')))
    }
}

/** "02 - Dune Messiah" → 2.0 and "Dune Messiah"; "Book 2.5 - Title" and "02. Title" too, but not "11.22.63". */
private val NUMBERED_FOLDER = Regex("""^(?:book\s*)?(\d{1,3}(?:\.\d+)?)(?:\s*[-_]\s*|\.\s+)(.+)$""", RegexOption.IGNORE_CASE)

/**
 * The book's details: book.json's where it has them, otherwise from the folder
 * (Audiobooks/Author/Series/Book) and the first file's tags. [folder] is relative to
 * Audiobooks/; [cover] is the cover picture's name and change time.
 */
fun bookFrom(folder: String, json: BookJson?, files: List<BookFile>, cover: Pair<String, Long>?): Book {
    val parts = folder.split('/').filter { it.isNotEmpty() }
    val folderName = parts.lastOrNull() ?: folder
    val numbered = NUMBERED_FOLDER.matchEntire(folderName)
    val folderAuthor = parts.takeIf { it.size >= 2 }?.first()
    val folderSeries = parts.takeIf { it.size >= 3 }?.get(parts.size - 2)
    return Book(
        id = json?.id ?: folder,
        folder = folder,
        title = json?.title ?: numbered?.groupValues?.get(2)?.trim() ?: folderName,
        author = json?.author ?: folderAuthor ?: files.firstNotNullOfOrNull { it.tagArtist } ?: UNKNOWN_AUTHOR,
        narrator = json?.narrator.orEmpty(),
        series = if (json != null) json.series else folderSeries,
        seriesNumber = if (json != null) json.seriesNumber else numbered?.groupValues?.get(1)?.toDoubleOrNull(),
        year = json?.year.orEmpty(),
        coverFile = cover?.first,
        coverModified = cover?.second ?: 0,
        files = files,
        hasBookJson = json != null,
    )
}

private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private val DIGITS = Regex("""\d+|\D+""")

/** "2.mp3" before "10.mp3": runs of digits compare as numbers, the rest ignoring case. */
val NATURAL_ORDER: Comparator<String> = Comparator { a, b ->
    val x = DIGITS.findAll(a.lowercase()).map { it.value }.toList()
    val y = DIGITS.findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(x.size, y.size)) {
        val p = x[i]
        val q = y[i]
        val result = if (p[0].isDigit() && q[0].isDigit()) {
            val trimmedP = p.trimStart('0')
            val trimmedQ = q.trimStart('0')
            if (trimmedP.length != trimmedQ.length) trimmedP.length - trimmedQ.length else trimmedP.compareTo(trimmedQ)
        } else {
            p.compareTo(q)
        }
        if (result != 0) return@Comparator result
    }
    if (x.size != y.size) x.size - y.size else a.compareTo(b)
}
