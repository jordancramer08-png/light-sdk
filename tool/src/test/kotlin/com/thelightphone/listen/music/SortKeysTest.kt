package com.thelightphone.listen.music

import kotlin.test.Test
import kotlin.test.assertEquals

class SortKeysTest {

    @Test
    fun `leading articles are ignored`() {
        assertEquals("corner room", sortKey("The Corner Room"))
        assertEquals("day to remember", sortKey("A Day to Remember"))
        assertEquals("ocean", sortKey("An Ocean"))
        assertEquals("keith green", sortKey("Keith Green"))
    }

    @Test
    fun `an article alone or inside a word stays`() {
        assertEquals("the", sortKey("The"))
        assertEquals("theory", sortKey("Theory"))
        assertEquals("abba", sortKey("ABBA"))
    }

    @Test
    fun `case and accents are ignored`() {
        assertEquals(sortKey("Beyonce"), sortKey("Beyoncé"))
        assertEquals(sortKey("ÉLAN"), sortKey("elan"))
    }

    @Test
    fun `songs sort by title then artist`() {
        fun song(title: String, artist: String) = Song("$artist/$title.mp3", 1, 1, title, artist, artist, "Album")
        val sorted = sortedByTitle(
            listOf(song("The Zebra", "X"), song("apple", "B"), song("Apple", "A"), song("Éclair", "C"), song("Banana", "D")),
        )
        assertEquals(listOf("Apple", "apple", "Banana", "Éclair", "The Zebra"), sorted.map { it.title })
    }

    @Test
    fun `fast plain-text keys match the full Unicode ones`() {
        // What the keys were before the fast path (regular expressions over every name).
        fun slow(name: String) = java.text.Normalizer.normalize(name.trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""\s+"""), " ")
            .lowercase(java.util.Locale.ROOT)
        val names = listOf("Shane & Shane", "  The Corner   Room ", "Beyoncé", "SIGUR RÓS", "Mötley	Crüe", "Ólafur Arnalds", "AC/DC")
        for (name in names) assertEquals(slow(name), groupKey(name), name)
        assertEquals("corner room", sortKey("The Corner Room"))
        assertEquals("sigur ros", sortKey("Sigur Rós"))
    }
}
