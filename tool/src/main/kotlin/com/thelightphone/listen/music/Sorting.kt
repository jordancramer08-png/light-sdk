package com.thelightphone.listen.music

import kotlinx.serialization.Serializable

/** What the Songs list can be sorted by. */
enum class SongSortField(val label: String) { TITLE("Title"), ARTIST("Artist"), ALBUM("Album") }

/** What the Albums list can be sorted by. */
enum class AlbumSortField(val label: String) { ALBUM("Album"), ARTIST("Artist") }

/**
 * One list's sort: [field] is the name of a field enum entry (kept as text so an old or
 * hand-edited settings.json still loads), and [descending] means Z–A.
 */
@Serializable
data class ListSort(val field: String = "", val descending: Boolean = false)

/** The field [ListSort.field] names, or [default] when it's missing or unknown. */
inline fun <reified E : Enum<E>> ListSort.fieldOr(default: E): E =
    enumValues<E>().firstOrNull { it.name == field } ?: default

/** "Title A–Z", "Artist Z–A". */
fun sortLabel(field: String, descending: Boolean): String = "$field ${if (descending) "Z–A" else "A–Z"}"

/**
 * A song with its sort keys worked out once, so sorting 1,400 songs doesn't normalize
 * every name again at each comparison.
 */
private class SongKeys(val song: Song) {
    val title = sortKey(song.title)
    val artist = sortKey(song.artist)
    val album = sortKey(song.album)
    val albumArtist = sortKey(song.albumArtist)
}

/**
 * Songs by [field], A–Z or Z–A ([descending]). Only the chosen field is reversed: songs of
 * one album stay in track order, and ties by the same artist stay A–Z.
 */
fun sortSongs(songs: List<Song>, field: SongSortField, descending: Boolean): List<Song> {
    val primary: Comparator<SongKeys> = when (field) {
        SongSortField.TITLE -> compareBy { it.title }
        SongSortField.ARTIST -> compareBy { it.artist }
        SongSortField.ALBUM -> compareBy { it.album }
    }
    val rest: Comparator<SongKeys> = when (field) {
        SongSortField.TITLE -> compareBy({ it.artist }, { it.song.path })
        SongSortField.ARTIST -> compareBy<SongKeys>({ it.album }).thenBy(TRACK_ORDER) { it.song }
        SongSortField.ALBUM -> compareBy<SongKeys>({ it.albumArtist }).thenBy(TRACK_ORDER) { it.song }
    }
    val order = (if (descending) primary.reversed() else primary).then(rest)
    return songs.map(::SongKeys).sortedWith(order).map { it.song }
}

private class AlbumKeys(val album: Album) {
    val title = sortKey(album.title)
    val artist = sortKey(album.artist)
}

/** Albums by name or by artist (then year, then name), A–Z or Z–A. */
fun sortAlbums(albums: List<Album>, field: AlbumSortField, descending: Boolean): List<Album> {
    val primary: Comparator<AlbumKeys> = when (field) {
        AlbumSortField.ALBUM -> compareBy { it.title }
        AlbumSortField.ARTIST -> compareBy { it.artist }
    }
    val rest: Comparator<AlbumKeys> = when (field) {
        AlbumSortField.ALBUM -> compareBy({ it.artist }, { it.album.key })
        AlbumSortField.ARTIST -> compareBy<AlbumKeys, Album>(ARTIST_ALBUM_ORDER) { it.album }
    }
    val order = (if (descending) primary.reversed() else primary).then(rest)
    return albums.map(::AlbumKeys).sortedWith(order).map { it.album }
}

/** Artists by name, A–Z or Z–A. */
fun sortArtists(artists: List<Artist>, descending: Boolean): List<Artist> {
    val keyed = artists.map { sortKey(it.name) to it }
    val primary = compareBy<Pair<String, Artist>> { it.first }
    val order = (if (descending) primary.reversed() else primary).thenBy { it.second.key }
    return keyed.sortedWith(order).map { it.second }
}
