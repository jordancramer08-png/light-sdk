package com.thelightphone.listen.music

/**
 * One genre: every song whose genre tag has this [key] (spellings that differ only in
 * capitals, accents or spaces are one genre). [name] is the spelling most of its songs use;
 * the songs with no genre form [NO_GENRE]. [albums] are the albums with at least one of its
 * songs, A–Z; [songs] are those songs, album by album, each album in track order.
 */
data class Genre(val key: String, val name: String, val albums: List<Album>, val songs: List<Song>) {
    val isNoGenre: Boolean get() = key == NO_GENRE_KEY
}

const val NO_GENRE = "No Genre"
const val NO_GENRE_KEY = ""

/** "Christian Rock", "christian  rock" and "ChristianRock" → "christianrock"; no genre → "". */
fun genreKey(genre: String): String = plainLowercase(genre).filterNot { it.isWhitespace() }

/** The genres of [albums]' songs, A–Z by name, "No Genre" last. */
fun groupGenres(albums: List<Album>): List<Genre> {
    val byKey = LinkedHashMap<String, MutableList<Song>>()
    val ordered = albums.map { sortKey(it.title) to it }.sortedWith(compareBy({ it.first }, { it.second.key })).map { it.second }
    for (album in ordered) {
        for (song in album.songs) byKey.getOrPut(genreKey(song.genre)) { mutableListOf() } += song
    }
    val genres = byKey.map { (key, songs) ->
        val name = if (key == NO_GENRE_KEY) {
            NO_GENRE
        } else {
            songs.groupingBy { it.genre }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key })).first().key
        }
        val albumKeys = songs.map { it.albumKey }.toSet()
        Genre(key, name, ordered.filter { it.key in albumKeys }, songs)
    }
    return sortGenres(genres)
}

/** A–Z by name (ignoring a leading The/A/An, like every list), with "No Genre" always last. */
fun sortGenres(genres: List<Genre>): List<Genre> =
    genres.map { sortKey(it.name) to it }
        .sortedWith(compareBy({ it.second.isNoGenre }, { it.first }, { it.second.key }))
        .map { it.second }
