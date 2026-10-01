package com.thelightphone.listen.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchTest {

    private fun song(artist: String, album: String, title: String) =
        Song("$artist/$album/$title.mp3", 1, 1, title, artist, artist, album)

    private val songs = listOf(
        song("Keith & Kristyn Getty", "Hymns for the Christian Life", "In Christ Alone"),
        song("Keith & Kristyn Getty", "Hymns for the Christian Life", "It Is Well"),
        song("Audrey Assad", "Fortunate Fall", "Wellspring"),
        song("Beyoncé", "Lemonade", "Don't Hurt Yourself"),
        song("The Corner Room", "Well Water", "Farewell"),
        song("Keith Green", "No Compromise", "Make My Life a Prayer"),
    )
    private val albums = groupAlbums(songs)
    private val index = MusicSearchIndex(songs, groupArtists(albums), albums)

    @Test
    fun `part of a name finds the artist`() {
        assertEquals(listOf("Keith & Kristyn Getty"), index.search("getty").artists.map { it.name })
    }

    @Test
    fun `capitals and accents are ignored`() {
        assertEquals(listOf("Beyoncé"), index.search("BEYONCE").artists.map { it.name })
        assertEquals(listOf("Beyoncé"), index.search("beyoncé").artists.map { it.name })
    }

    @Test
    fun `a word anywhere in a title matches, starts first`() {
        val found = index.search("well")
        // Starts with "well" first, then a word starting with it, then inside a word.
        assertEquals(listOf("Wellspring", "It Is Well", "Farewell"), found.songs.map { it.title })
        assertEquals(listOf("Well Water"), found.albums.map { it.title })
    }

    @Test
    fun `every word must match, in any order`() {
        assertEquals(listOf("Keith & Kristyn Getty"), index.search("kristyn keith").artists.map { it.name })
        assertEquals(listOf("Keith & Kristyn Getty", "Keith Green"), index.search("keith").artists.map { it.name })
        assertTrue(index.search("keith zebra").isEmpty)
    }

    @Test
    fun `apostrophes and extra spaces are ignored`() {
        assertEquals(listOf("Don't Hurt Yourself"), index.search("dont  hurt").songs.map { it.title })
        assertEquals(listOf("Don't Hurt Yourself"), index.search("don’t").songs.map { it.title })
    }

    @Test
    fun `a blank search finds nothing`() {
        assertTrue(index.search("").isEmpty)
        assertTrue(index.search("   ").isEmpty)
    }
}
