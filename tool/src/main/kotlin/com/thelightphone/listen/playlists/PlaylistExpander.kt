package com.thelightphone.listen.playlists

import com.thelightphone.listen.music.Album
import com.thelightphone.listen.music.MusicLibraryState
import com.thelightphone.listen.music.Song
import com.thelightphone.listen.music.albumKey
import com.thelightphone.listen.music.groupKey
import com.thelightphone.listen.music.songFrom

/**
 * What one entry matches in the library right now: its [songs] in play order, and the
 * [albums] they come from (for the entry's cover). Nothing matched = not on the phone.
 */
data class EntryMatch(val entry: PlaylistEntry, val songs: List<Song>, val albums: List<Album>) {
    val onPhone: Boolean get() = songs.isNotEmpty()
}

/**
 * Matches [entry] against the current [library]:
 * - a song: that file, if it's still there
 * - an album: its songs in disc and track order
 * - an artist: every album whose album artist is them, all its songs; plus, on other
 *   albums, the songs they perform (track artist). Album by album, by year, then title.
 */
fun matchEntry(entry: PlaylistEntry, library: MusicLibraryState): EntryMatch =
    when (entry.kind) {
        PlaylistEntry.KIND_SONG -> {
            val song = library.song(entry.path)
            EntryMatch(entry, listOfNotNull(song), listOfNotNull(song?.let(library::albumOf)))
        }
        PlaylistEntry.KIND_ALBUM -> {
            val album = library.album(albumKey(entry.artist, entry.album))
            EntryMatch(entry, album?.songs.orEmpty(), listOfNotNull(album))
        }
        PlaylistEntry.KIND_ARTIST -> {
            val key = groupKey(entry.artist)
            val albums = mutableListOf<Album>()
            val songs = mutableListOf<Song>()
            for (album in library.albumsInArtistOrder) {
                val theirs = if (library.artistKey(album.artist) == key) {
                    album.songs
                } else {
                    album.songs.filter { library.artistKey(it.artist) == key }
                }
                if (theirs.isNotEmpty()) {
                    albums += album
                    songs += theirs
                }
            }
            EntryMatch(entry, songs, albums)
        }
        else -> EntryMatch(entry, emptyList(), emptyList())
    }

fun matchEntries(playlist: Playlist, library: MusicLibraryState): List<EntryMatch> =
    playlist.entries.map { matchEntry(it, library) }

/** Every matched song, entry by entry; a song matched by more than one entry plays once (the first time). */
fun playlistSongs(matches: List<EntryMatch>): List<Song> {
    val seen = HashSet<String>()
    return matches.flatMap { it.songs }.filter { seen.add(it.path) }
}

fun playlistSongs(playlist: Playlist, library: MusicLibraryState): List<Song> =
    playlistSongs(matchEntries(playlist, library))

/** The entry's name: the song title, album name or artist name (from the path when the song is gone). */
fun entryTitle(match: EntryMatch): String {
    val entry = match.entry
    return when (entry.kind) {
        PlaylistEntry.KIND_SONG -> (match.songs.firstOrNull() ?: songFromPath(entry.path)).title
        PlaylistEntry.KIND_ALBUM -> entry.album
        PlaylistEntry.KIND_ARTIST -> entry.artist
        else -> entry.album.ifEmpty { entry.artist.ifEmpty { entry.path } }
    }
}

/** What kind of entry, and whose: "Song · Keith Green", "Album · Shane & Shane", "Artist". */
fun entryKindLine(match: EntryMatch): String {
    val entry = match.entry
    return when (entry.kind) {
        PlaylistEntry.KIND_SONG -> "Song · " + (match.songs.firstOrNull() ?: songFromPath(entry.path)).artist
        PlaylistEntry.KIND_ALBUM -> "Album · " + entry.artist
        PlaylistEntry.KIND_ARTIST -> "Artist"
        else -> "Unknown"
    }
}

/** A song's names from its folders and file name only (for a song no longer on the phone). */
private fun songFromPath(path: String): Song = songFrom(path, 0, 0, tags = null)

const val NOT_ON_PHONE = "Not on phone"
