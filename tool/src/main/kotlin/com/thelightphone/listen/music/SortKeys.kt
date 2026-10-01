package com.thelightphone.listen.music

import java.text.Normalizer
import java.util.Locale

private val LEADING_ARTICLES = listOf("the ", "a ", "an ")

/**
 * The key a name sorts by: case- and accent-insensitive, and without a leading "The ",
 * "A " or "An " (so "The Corner Room" sorts under C and "Beyoncé" next to "Beyonce").
 */
fun sortKey(name: String): String {
    val plain = plainLowercase(name.trim())
    for (article in LEADING_ARTICLES) {
        if (plain.startsWith(article) && plain.length > article.length) {
            return plain.substring(article.length).trimStart()
        }
    }
    return plain
}

/**
 * [text] in lower case without accents ("Beyoncé" → "beyonce"). Most names are plain ASCII
 * and skip the Unicode work: it runs thousands of times while the library loads, and on the
 * phone the slow way took seconds.
 */
fun plainLowercase(text: String): String {
    if (text.all { it.code < 0x80 }) return text.lowercase(Locale.ROOT)
    val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
    val plain = StringBuilder(decomposed.length)
    for (c in decomposed) {
        if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) plain.append(c)
    }
    return plain.toString().lowercase(Locale.ROOT)
}

/** Songs A–Z by title, then artist; the path keeps the order stable for exact ties. */
fun sortedByTitle(songs: List<Song>): List<Song> =
    songs
        .map { Triple(sortKey(it.title), sortKey(it.artist), it) }
        .sortedWith(compareBy({ it.first }, { it.second }, { it.third.path }))
        .map { it.third }
