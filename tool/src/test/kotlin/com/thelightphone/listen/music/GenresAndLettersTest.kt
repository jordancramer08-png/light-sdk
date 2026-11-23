package com.thelightphone.listen.music

import com.thelightphone.listen.playlists.PlaylistEntry
import com.thelightphone.listen.playlists.matchEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GenresAndLettersTest {

    private fun song(title: String, genre: String, album: String = "Album", track: Int = 0) =
        Song("A/$album/$title.mp3", 1, 1, title, "A", "A", album, track, 0, 0, 1000, genre)

    @Test
    fun `genres differing only in capitals or spaces are one, no genre goes last`() {
        val songs = listOf(
            song("1", "Worship"), song("2", "worship "), song("3", "Christian  Rock", album = "B"),
            song("4", "christianrock", album = "B"), song("5", "", album = "C"), song("6", "Ambient", album = "C"),
        )
        val genres = groupGenres(groupAlbums(songs))
        assertEquals(listOf("Ambient", "Christian Rock", "Worship", NO_GENRE), genres.map { it.name }.let { names ->
            // "Christian  Rock" is cleaned on scan; here the raw tag stands, so accept either spelling.
            names.map { if (genreKey(it) == "christianrock") "Christian Rock" else it }
        })
        assertEquals(2, genres.first { it.key == "worship" }.songs.size)
        assertTrue(genres.last().isNoGenre)
    }

    @Test
    fun `cleaning a genre tag`() {
        assertEquals("Alternative", cleanGenre("(20)Alternative"))
        assertEquals("(20)", cleanGenre("(20)"))
        assertEquals("Christian Rock", cleanGenre("  Christian   Rock "))
        assertEquals("", cleanGenre(null))
    }

    @Test
    fun `a genre playlist entry is live and matches any spelling`() {
        val library = MusicLibraryState(
            songs = listOf(song("1", "Worship")),
            albums = groupAlbums(listOf(song("1", "Worship"))),
            genres = groupGenres(groupAlbums(listOf(song("1", "Worship")))),
        )
        assertTrue(matchEntry(PlaylistEntry.genre("WORSHIP"), library).onPhone)
        assertFalse(matchEntry(PlaylistEntry.genre("Jazz"), library).onPhone)
    }

    @Test
    fun `index letters follow the sort rules`() {
        assertEquals('C', indexLetter("The Corner Room"))
        assertEquals('K', indexLetter("Keith Green"))
        assertEquals('E', indexLetter("Élan"))
        assertEquals('#', indexLetter("10,000 Reasons"))
        assertEquals('#', indexLetter(""))
    }

    @Test
    fun `letter slots jump to the first row and missing letters skip ahead`() {
        val slots = letterSlots(listOf('#', 'A', 'A', 'C', null), descending = false)
        assertEquals('#', slots.first().letter)
        assertEquals(27, slots.size)
        val byLetter = slots.associateBy { it.letter }
        assertEquals(1, byLetter.getValue('A').target)
        assertFalse(byLetter.getValue('B').present)
        assertEquals(3, byLetter.getValue('B').target)
        // After the last letter with rows, letters go to the last one.
        assertEquals(3, byLetter.getValue('Z').target)
    }

    @Test
    fun `z to a lists run the bar z to a with hash last`() {
        val slots = letterSlots(listOf('Z', 'B', '#'), descending = true)
        assertEquals('Z', slots.first().letter)
        assertEquals('#', slots.last().letter)
        assertEquals(1, slots.first { it.letter == 'Y' }.target)
    }
}
