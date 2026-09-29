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
}
