package com.thelightphone.listen.music


/**
 * One album: every song with the same album artist and album name, in disc, then track
 * order. [key] identifies it (see [albumKey]); [title], [artist] and [year] are taken from
 * its songs. Grouped by album artist, never track artist, so an album with several
 * performers stays one album.
 */
data class Album(
    val key: String,
    val title: String,
    val artist: String,
    val year: Int,
    val songs: List<Song>,
) {
    val durationMs: Long get() = songs.sumOf { it.durationMs }

    /** Whether the songs span more than one disc (then the track list shows disc headings). */
    val hasSeveralDiscs: Boolean get() = songs.map { it.discOrder }.distinct().size > 1
}

/** One album artist and their albums, by year (albums without a year last), then title. */
data class Artist(val key: String, val name: String, val albums: List<Album>) {
    val songCount: Int get() = albums.sumOf { it.songs.size }

    /** Every song, album by album, each in track order. */
    val songs: List<Song> get() = albums.flatMap { it.songs }
}

/**
 * A name as used for grouping: trimmed, spaces collapsed, and case- and accent-insensitive,
 * so "Shane & Shane" and "Shane & shane" are one artist. (Unlike [sortKey], a leading
 * "The" is kept: "The Corner Room" and "Corner Room" could be different bands.)
 */
fun groupKey(name: String): String = collapseSpaces(plainLowercase(name.trim()))

/** Runs of spaces, tabs or line breaks as one space. */
private fun collapseSpaces(text: String): String {
    if (text.none { it.isWhitespace() && it != ' ' } && !text.contains("  ")) return text
    val out = StringBuilder(text.length)
    var inSpace = false
    for (c in text) {
        if (c.isWhitespace()) {
            if (!inSpace) out.append(' ')
            inSpace = true
        } else {
            out.append(c)
            inSpace = false
        }
    }
    return out.toString()
}

/** The album a song belongs to: its album artist and album name. */
fun albumKey(albumArtist: String, album: String): String = groupKey(albumArtist) + "\u0000" + groupKey(album)

val Song.albumKey: String get() = albumKey(albumArtist, album)

/** A missing disc number counts as disc 1. */
val Song.discOrder: Int get() = if (disc > 0) disc else 1

/** Songs of one album in play order: disc, then track, then file path (for untagged files). */
val TRACK_ORDER: Comparator<Song> = compareBy<Song>({ it.discOrder }, { it.track }, { it.path })

/** The songs grouped into albums (in no particular order; the screens sort them). */
fun groupAlbums(songs: List<Song>): List<Album> =
    songs.groupBy { it.albumKey }.map { (key, group) ->
        val ordered = group.sortedWith(TRACK_ORDER)
        val first = ordered.first()
        Album(
            key = key,
            title = first.album,
            artist = first.albumArtist,
            year = ordered.firstOrNull { it.year > 0 }?.year ?: 0,
            songs = ordered,
        )
    }

/** Albums of one artist: by year (no year last), then title. */
val ARTIST_ALBUM_ORDER: Comparator<Album> =
    compareBy<Album>({ if (it.year > 0) it.year else Int.MAX_VALUE }, { sortKey(it.title) }, { it.key })

/** The album artists and their albums (in no particular order; the screens sort them). */
fun groupArtists(albums: List<Album>): List<Artist> =
    albums.groupBy { groupKey(it.artist) }.map { (key, group) ->
        val ordered = group.sortedWith(ARTIST_ALBUM_ORDER)
        Artist(key = key, name = ordered.first().artist, albums = ordered)
    }
