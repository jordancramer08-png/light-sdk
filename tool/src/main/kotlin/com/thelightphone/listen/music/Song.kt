package com.thelightphone.listen.music

import kotlinx.serialization.Serializable

/**
 * One song file and its tags, as saved in the index cache. [path] is relative to
 * /sdcard/Listen/Music with "/" separators (the same form playlists store). [size] and
 * [modified] tell the scanner whether the file changed since its tags were read.
 * Numbers that were missing are 0.
 */
@Serializable
data class Song(
    val path: String,
    val size: Long,
    val modified: Long,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val track: Int = 0,
    val disc: Int = 0,
    val year: Int = 0,
    val durationMs: Long = 0,
)

/**
 * The tags exactly as read from a file; any of them may be missing. Kept apart from the
 * Android tag reader so the fallback rules below can be tested on the PC.
 */
data class RawTags(
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val track: String? = null,
    val disc: String? = null,
    val year: String? = null,
    val durationMs: String? = null,
)

const val UNKNOWN_ARTIST = "Unknown artist"
const val UNKNOWN_ALBUM = "Unknown album"

/**
 * Builds a [Song] from a file's tags ([tags] is null when the file couldn't be read),
 * falling back to the folder names (Artist/Album/file) and the file name.
 *
 * The album artist falls back to the Artist folder before the track artist, so an album
 * without an album-artist tag still stays one album when its songs have different
 * performers (albums are grouped by album artist, never track artist).
 */
fun songFrom(path: String, size: Long, modified: Long, tags: RawTags?): Song {
    val parts = path.split('/').filter { it.isNotEmpty() }
    val folderArtist = if (parts.size >= 2) parts[0] else null
    val folderAlbum = if (parts.size >= 3) parts[1] else null
    val trackArtist = tags?.artist.clean()
    val albumArtist = tags?.albumArtist.clean() ?: folderArtist.clean() ?: trackArtist
    return Song(
        path = path,
        size = size,
        modified = modified,
        title = tags?.title.clean() ?: titleFromFileName(parts.lastOrNull() ?: path),
        artist = trackArtist ?: albumArtist ?: UNKNOWN_ARTIST,
        albumArtist = albumArtist ?: UNKNOWN_ARTIST,
        album = tags?.album.clean() ?: folderAlbum.clean() ?: UNKNOWN_ALBUM,
        track = leadingNumber(tags?.track),
        disc = leadingNumber(tags?.disc),
        year = yearOf(tags?.year),
        durationMs = tags?.durationMs?.trim()?.toLongOrNull() ?: 0,
    )
}

/** Trimmed, or null when blank. */
private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** A leading track number such as "01 - ", "01. ", "1-03 " or "07 ". */
private val TRACK_PREFIX = Regex("""^(\d{1,2}[-.])?\d{1,3}(\s*[-._]\s*|\s+)""")

/** "03 - Song Name.mp3" → "Song Name". Keeps the number if that's all the name is. */
fun titleFromFileName(fileName: String): String {
    val base = fileName.substringBeforeLast('.', fileName).trim()
    val stripped = base.replaceFirst(TRACK_PREFIX, "").trim()
    return stripped.ifEmpty { base }
}

/** "3/12" → 3, " 07 " → 7; anything without a leading number → 0. */
fun leadingNumber(value: String?): Int =
    value?.trim()?.takeWhile { it.isDigit() }?.take(4)?.toIntOrNull() ?: 0

/** The first four-digit year in "2015", "2015-04-28" or "20150428T000000.000Z"; else 0. */
fun yearOf(value: String?): Int =
    value?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() } ?: 0
