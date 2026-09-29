package com.thelightphone.listen.music

import com.thelightphone.listen.storage.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The saved result of the last music scan, so the lists show at once on launch. [stamp]
 * is [MusicScanner.stamp] at the time of that scan. It lives in the app's own files: it
 * can always be rebuilt from the music on the phone.
 */
@Serializable
data class MusicIndex(
    val version: Int = VERSION,
    val stamp: String = "",
    val songs: List<Song> = emptyList(),
) {
    companion object {
        /** Bump when [Song] changes meaning, so old caches are thrown away and rebuilt. */
        const val VERSION = 1
    }
}

/** Reads and writes [MusicIndex] as JSON in [file]. */
class MusicIndexStore(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true }

    /** The saved index, or an empty one when it's missing, broken or from an older version. */
    fun load(): MusicIndex {
        val text = AtomicFile.readTextOrNull(file) ?: return MusicIndex()
        val index = try {
            json.decodeFromString(MusicIndex.serializer(), text)
        } catch (e: Exception) {
            return MusicIndex()
        }
        return if (index.version == MusicIndex.VERSION) index else MusicIndex()
    }

    fun save(index: MusicIndex) {
        AtomicFile.writeText(file, json.encodeToString(MusicIndex.serializer(), index))
    }
}
