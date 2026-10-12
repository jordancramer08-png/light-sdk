package com.jordan.watch.logic

import java.text.Normalizer

/**
 * Search over title, cast and director. Every typed word must appear somewhere, in any
 * order, ignoring capitals, accents and apostrophes ("amelie" finds "Amélie",
 * "oceans" finds "Ocean's Eleven").
 */
object Search {

    fun filter(items: List<LibraryItem>, query: String): List<LibraryItem> {
        val words = words(query)
        if (words.isEmpty()) return items
        return items.filter { matchesWords(haystack(it), words) }
    }

    fun matches(item: LibraryItem, query: String): Boolean {
        val words = words(query)
        return words.isEmpty() || matchesWords(haystack(item), words)
    }

    /** Lower case, accents removed, apostrophes dropped, other punctuation turned to spaces. */
    fun normalize(text: String): String {
        val noAccents = Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "")
        return noAccents.lowercase()
            .replace(APOSTROPHES, "")
            .replace(NON_WORD, " ")
            .trim()
    }

    private fun words(query: String): List<String> = normalize(query).split(' ').filter { it.isNotEmpty() }

    private fun haystack(item: LibraryItem): String =
        normalize((listOf(item.title, item.director.orEmpty()) + item.cast).joinToString(" "))

    private fun matchesWords(haystack: String, words: List<String>): Boolean = words.all { haystack.contains(it) }

    private val MARKS = Regex("\\p{Mn}+")
    private val APOSTROPHES = Regex("['‘’`]")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
}
