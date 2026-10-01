package com.thelightphone.listen.music

/** What a search found, each group best match first. */
data class SearchResults(
    val songs: List<Song> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
) {
    val isEmpty: Boolean get() = songs.isEmpty() && artists.isEmpty() && albums.isEmpty()
}

/**
 * The library ready to search: every song title, artist name and album title worked out
 * once in [searchText] form, so each key press only compares plain strings (well under a
 * millisecond for 1,400 songs).
 */
class MusicSearchIndex(songs: List<Song>, artists: List<Artist>, albums: List<Album>) {
    private val songs = songs.map { Entry(searchText(it.title), sortKey(it.title), it.path, it) }
    private val artists = artists.map { Entry(searchText(it.name), sortKey(it.name), it.key, it) }
    private val albums = albums.map { Entry(searchText(it.title), sortKey(it.title), it.key, it) }

    /**
     * Songs by title, artists by name and albums by title that contain every word of [query],
     * anywhere and in any order, ignoring capitals, accents and apostrophes ("getty" finds
     * Keith & Kristyn Getty, "well" finds It Is Well). A blank query finds nothing.
     */
    fun search(query: String): SearchResults {
        val words = searchText(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return SearchResults()
        return SearchResults(songs = find(songs, words), artists = find(artists, words), albums = find(albums, words))
    }

    private class Entry<T>(val text: String, val sortKey: String, val tieBreak: String, val item: T)

    /**
     * The entries containing every word, best first: names that start with the query, then
     * names with a word starting with the first query word, then the rest; A–Z within each.
     */
    private fun <T> find(entries: List<Entry<T>>, words: List<String>): List<T> {
        val whole = words.joinToString(" ")
        val first = words.first()
        return entries
            .filter { entry -> words.all { entry.text.contains(it) } }
            .sortedWith(
                compareBy<Entry<T>>(
                    { rank(it.text, whole, first) },
                    { it.sortKey },
                    { it.tieBreak },
                ),
            )
            .map { it.item }
    }

    private fun rank(text: String, whole: String, first: String): Int = when {
        text.startsWith(whole) -> 0
        text.startsWith(first) || text.contains(" $first") -> 1
        else -> 2
    }
}

/**
 * [text] as search compares it: lower case without accents or apostrophes, with spaces
 * collapsed ("Don't Fear" → "dont fear", "Beyoncé" → "beyonce").
 */
fun searchText(text: String): String =
    groupKey(text.filterNot { it == '\'' || it == '’' || it == '‘' || it == '`' })
