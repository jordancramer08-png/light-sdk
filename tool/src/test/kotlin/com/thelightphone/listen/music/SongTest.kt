package com.thelightphone.listen.music

import kotlin.test.Test
import kotlin.test.assertEquals

class SongTest {

    @Test
    fun `tags win over folder names`() {
        val song = songFrom(
            "Folder Artist/Folder Album/01 - file.mp3", 10, 20,
            RawTags(
                title = "Real Title", artist = "Track Artist", albumArtist = "Album Artist",
                album = "Real Album", track = "3/12", disc = "2/2", year = "2015-04-28", durationMs = "215000",
            ),
        )
        assertEquals("Real Title", song.title)
        assertEquals("Track Artist", song.artist)
        assertEquals("Album Artist", song.albumArtist)
        assertEquals("Real Album", song.album)
        assertEquals(3, song.track)
        assertEquals(2, song.disc)
        assertEquals(2015, song.year)
        assertEquals(215000, song.durationMs)
    }

    @Test
    fun `unreadable file falls back to folders and file name`() {
        val song = songFrom("Keith Green/So You Wanna Go Back/07 - Pledge My Head.mp3", 1, 1, null)
        assertEquals("Pledge My Head", song.title)
        assertEquals("Keith Green", song.artist)
        assertEquals("Keith Green", song.albumArtist)
        assertEquals("So You Wanna Go Back", song.album)
        assertEquals(0, song.track)
    }

    @Test
    fun `blank tags count as missing`() {
        val song = songFrom("A/B/song.mp3", 1, 1, RawTags(title = "  ", artist = "", album = " "))
        assertEquals("song", song.title)
        assertEquals("A", song.artist)
        assertEquals("B", song.album)
    }

    @Test
    fun `missing album artist uses the artist folder, not the track artist`() {
        // A soundtrack with two composers stays one album.
        val first = songFrom("Soundtrack Artist/Age of Ultron/01.mp3", 1, 1, RawTags(artist = "Brian Tyler", album = "Age of Ultron"))
        val second = songFrom("Soundtrack Artist/Age of Ultron/02.mp3", 1, 1, RawTags(artist = "Danny Elfman", album = "Age of Ultron"))
        assertEquals("Soundtrack Artist", first.albumArtist)
        assertEquals(first.albumArtist, second.albumArtist)
        assertEquals("Brian Tyler", first.artist)
    }

    @Test
    fun `file directly in Music has unknown album`() {
        val song = songFrom("loose.flac", 1, 1, null)
        assertEquals("loose", song.title)
        assertEquals(UNKNOWN_ARTIST, song.artist)
        assertEquals(UNKNOWN_ALBUM, song.album)
    }

    @Test
    fun `track number prefixes are removed from file names`() {
        assertEquals("Song", titleFromFileName("01 - Song.mp3"))
        assertEquals("Song", titleFromFileName("01. Song.mp3"))
        assertEquals("Song", titleFromFileName("1-03 Song.m4a"))
        assertEquals("Song Name", titleFromFileName("07 Song Name.mp3"))
        assertEquals("1999", titleFromFileName("1999.mp3"))
        assertEquals("42", titleFromFileName("42.mp3"))
    }

    @Test
    fun `numbers are read leniently`() {
        assertEquals(3, leadingNumber("3/12"))
        assertEquals(7, leadingNumber(" 07 "))
        assertEquals(0, leadingNumber("x"))
        assertEquals(0, leadingNumber(null))
        assertEquals(2015, yearOf("20150428T000000.000Z"))
        assertEquals(0, yearOf("unknown"))
    }
}
