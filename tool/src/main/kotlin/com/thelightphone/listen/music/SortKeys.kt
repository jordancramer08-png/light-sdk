package com.thelightphone.listen.music

import java.text.Normalizer
import java.util.Locale

private val COMBINING_MARKS = Regex("""\p{Mn}+""")
private val LEADING_ARTICLES = listOf("the ", "a ", "an ")

/**
 * The key a name sorts by: case- and accent-insensitive, and without a leading "The ",
 * "A " or "An " (so "The Corner Room" sorts under C and "Beyoncé" next to "Beyonce").
 */
fun sortKey(name: String): String {
    val plain = Normalizer.normalize(name.trim(), Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase(Locale.ROOT)
    for (article in LEADING_ARTICLES) {
        if (plain.startsWith(article) && plain.length > article.length) {
            return plain.substring(article.length).trimStart()
        }
    }
    return plain
}

/** Songs A–Z by title, then artist; the path keeps the order stable for exact ties. */
fun sortedByTitle(songs: List<Song>): List<Song> =
    songs
        .map { Triple(sortKey(it.title), sortKey(it.artist), it) }
        .sortedWith(compareBy({ it.first }, { it.second }, { it.third.path }))
        .map { it.third }
