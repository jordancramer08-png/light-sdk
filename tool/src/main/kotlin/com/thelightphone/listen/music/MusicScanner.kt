package com.thelightphone.listen.music

import java.io.File
import java.util.zip.CRC32

/** An audio file found under Music/: [path] relative to it, with its size and change time. */
data class FoundFile(val path: String, val size: Long, val modified: Long)

/** The audio formats ExoPlayer plays with its built-in extractors. */
val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav")

/**
 * Finds the song files under [musicDir] and reads their tags with [readTags], re-reading
 * only files that are new or whose size or change time differs from the last index.
 * Hidden files and folders (starting with ".") are skipped. Blocking: run off the main thread.
 */
class MusicScanner(
    private val musicDir: File,
    private val lastSyncFile: File,
    private val readTags: (File) -> RawTags?,
) {

    /**
     * A cheap fingerprint of the music folder: the PC script's last-sync.txt plus every
     * folder's change time (a folder's time changes when files are added to it or removed).
     * If it matches the index's, nothing changed and no scan is needed.
     */
    fun stamp(): String {
        val sync = lastSyncFile.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        if (!musicDir.isDirectory) return "sync=$sync;no-music"
        val folders = musicDir.walkTopDown()
            .onEnter { it == musicDir || !it.name.startsWith(".") }
            .filter { it.isDirectory }
            .map { it.path + "=" + it.lastModified() }
            .sorted()
            .toList()
        val crc = CRC32().apply { update(folders.joinToString("\n").toByteArray()) }
        return "sync=$sync;folders=${folders.size};crc=${crc.value}"
    }

    /** Every audio file under Music/, in no particular order. */
    fun findFiles(): List<FoundFile> {
        if (!musicDir.isDirectory) return emptyList()
        return musicDir.walkTopDown()
            .onEnter { it == musicDir || !it.name.startsWith(".") }
            .filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in AUDIO_EXTENSIONS }
            .map { FoundFile(it.relativeTo(musicDir).invariantSeparatorsPath, it.length(), it.lastModified()) }
            .toList()
    }

    /**
     * The songs now under Music/: unchanged ones are kept from [previous], the rest are read.
     * [onProgress] gets the songs found so far every [PROGRESS_EVERY] files read, so a long
     * first scan fills the list as it goes.
     */
    fun scan(previous: List<Song>, onProgress: (List<Song>) -> Unit = {}): List<Song> {
        val plan = planRescan(previous, findFiles())
        val songs = plan.kept.toMutableList()
        plan.toRead.forEachIndexed { index, found ->
            songs += songFrom(found.path, found.size, found.modified, readTags(File(musicDir, found.path)))
            if ((index + 1) % PROGRESS_EVERY == 0) onProgress(songs.toList())
        }
        return songs
    }

    private companion object {
        const val PROGRESS_EVERY = 50
    }
}

/** What a rescan does: [kept] songs need no reading; [toRead] files are new or changed. */
data class RescanPlan(val kept: List<Song>, val toRead: List<FoundFile>)

/**
 * Compares the files on the phone with the last index. A file whose path, size and change
 * time all match keeps its old tags; anything else is read again. Songs whose files are
 * gone simply drop out.
 */
fun planRescan(previous: List<Song>, files: List<FoundFile>): RescanPlan {
    val byPath = previous.associateBy { it.path }
    val kept = mutableListOf<Song>()
    val toRead = mutableListOf<FoundFile>()
    for (file in files) {
        val old = byPath[file.path]
        if (old != null && old.size == file.size && old.modified == file.modified) kept += old else toRead += file
    }
    return RescanPlan(kept, toRead)
}
