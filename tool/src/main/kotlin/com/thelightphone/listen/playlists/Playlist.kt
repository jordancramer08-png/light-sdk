package com.thelightphone.listen.playlists

import com.thelightphone.listen.music.albumKey
import com.thelightphone.listen.music.groupKey
import kotlinx.serialization.Serializable

/**
 * One line of a playlist. It names music rather than holding songs, so it's "live": it's
 * matched against whatever is on the phone each time the playlist is shown or played.
 * - [KIND_SONG]: [path] relative to /sdcard/Listen/Music
 * - [KIND_ALBUM]: [artist] (the album artist) and [album]
 * - [KIND_ARTIST]: [artist]
 * An unknown kind (from a newer Listen) is kept as it is and matches nothing.
 */
@Serializable
data class PlaylistEntry(
    val kind: String,
    val path: String = "",
    val artist: String = "",
    val album: String = "",
) {
    /** Entries that name the same music have the same key (names compared like the library does). */
    val matchKey: String
        get() = when (kind) {
            KIND_SONG -> "song:$path"
            KIND_ALBUM -> "album:" + albumKey(artist, album)
            KIND_ARTIST -> "artist:" + groupKey(artist)
            else -> "$kind:$path|$artist|$album"
        }

    companion object {
        const val KIND_SONG = "song"
        const val KIND_ALBUM = "album"
        const val KIND_ARTIST = "artist"

        fun song(path: String) = PlaylistEntry(KIND_SONG, path = path)
        fun album(albumArtist: String, album: String) = PlaylistEntry(KIND_ALBUM, artist = albumArtist, album = album)
        fun artist(name: String) = PlaylistEntry(KIND_ARTIST, artist = name)
    }
}

/** A named, ordered list of entries. [id] never changes, so renaming keeps everything else. */
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val entries: List<PlaylistEntry> = emptyList(),
) {
    fun contains(entry: PlaylistEntry): Boolean = entries.any { it.matchKey == entry.matchKey }

    /** With [entry] added at the end, unless the same music is already in the playlist. */
    fun adding(entry: PlaylistEntry): Playlist = if (contains(entry)) this else copy(entries = entries + entry)

    /** Without any entry naming the same music as [entry]. */
    fun removing(entry: PlaylistEntry): Playlist = copy(entries = entries.filterNot { it.matchKey == entry.matchKey })

    fun removingAt(index: Int): Playlist =
        if (index in entries.indices) copy(entries = entries.filterIndexed { i, _ -> i != index }) else this

    /** The entry at [index] swapped with the one above ([up]) or below it; unchanged at either end. */
    fun moving(index: Int, up: Boolean): Playlist {
        val other = if (up) index - 1 else index + 1
        if (index !in entries.indices || other !in entries.indices) return this
        val moved = entries.toMutableList()
        moved[index] = entries[other]
        moved[other] = entries[index]
        return copy(entries = moved)
    }
}

/**
 * What /sdcard/Listen/.state/playlists.json holds. Missing fields get these defaults and
 * unknown ones are ignored, so an older backup (or one from a newer Listen) still loads.
 */
@Serializable
data class PlaylistsData(
    val version: Int = 1,
    val playlists: List<Playlist> = emptyList(),
)

/** A typed playlist name with spaces tidied; null when nothing usable was typed. */
fun cleanPlaylistName(typed: String?): String? =
    typed?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
