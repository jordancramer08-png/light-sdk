package com.thelightphone.listen.music

import kotlin.test.Test
import kotlin.test.assertEquals

class AlbumsAndSortingTest {

    private fun song(
        title: String,
        artist: String = "Artist",
        albumArtist: String = artist,
        album: String = "Album",
        track: Int = 0,
        disc: Int = 0,
        year: Int = 0,
        path: String = "$albumArtist/$album/$title.mp3",
    ) = Song(path, 1, 1, title, artist, albumArtist, album, track, disc, year, 1000)

    @Test
    fun `an album with several performers stays one album`() {
        val songs = listOf(
            song("Heroes", artist = "Brian Tyler", albumArtist = "Brian Tyler & Danny Elfman", album = "Avengers: Age of Ultron", track = 1),
            song("New Avengers", artist = "Danny Elfman", albumArtist = "Brian Tyler & Danny Elfman", album = "Avengers: Age of Ultron", track = 2),
        )
        val albums = groupAlbums(songs)
        assertEquals(1, albums.size)
        assertEquals(listOf("Heroes", "New Avengers"), albums.single().songs.map { it.title })
        assertEquals("Brian Tyler & Danny Elfman", albums.single().artist)
    }

    @Test
    fun `album songs play in disc then track order`() {
        val songs = listOf(
            song("D2T1", track = 1, disc = 2),
            song("D1T2", track = 2, disc = 1),
            song("D1T1", track = 1, disc = 0), // no disc tag counts as disc 1
            song("D2T3", track = 3, disc = 2),
        )
        val album = groupAlbums(songs).single()
        assertEquals(listOf("D1T1", "D1T2", "D2T1", "D2T3"), album.songs.map { it.title })
        assertEquals(true, album.hasSeveralDiscs)
    }

    @Test
    fun `same album with different case or accents groups together, different artists apart`() {
        val songs = listOf(
            song("A", albumArtist = "Shane & Shane", album = "Hymns"),
            song("B", albumArtist = "Shane & shane", album = "hymns "),
            song("C", albumArtist = "Other", album = "Hymns"),
        )
        val albums = groupAlbums(songs)
        assertEquals(2, albums.size)
        assertEquals(1, groupArtists(albums).count { it.name.startsWith("Shane") })
    }

    @Test
    fun `an artist's albums are by year, undated last`() {
        val albums = groupAlbums(
            listOf(
                song("x", album = "Later", year = 2010),
                song("y", album = "No Year"),
                song("z", album = "Earlier", year = 1999),
            ),
        )
        val artist = groupArtists(albums).single()
        assertEquals(listOf("Earlier", "Later", "No Year"), artist.albums.map { it.title })
        assertEquals(3, artist.songCount)
    }

    @Test
    fun `songs sort by title, artist or album, both ways, ignoring The`() {
        val songs = listOf(
            song("Zebra", artist = "The Corner Room", album = "Bravo"),
            song("apple", artist = "Keith Green", album = "Alpha"),
            song("Mango", artist = "ABBA", album = "The Charlie"),
        )
        assertEquals(listOf("apple", "Mango", "Zebra"), sortSongs(songs, SongSortField.TITLE, false).map { it.title })
        assertEquals(listOf("Zebra", "Mango", "apple"), sortSongs(songs, SongSortField.TITLE, true).map { it.title })
        // ABBA, The Corner Room (under C), Keith Green
        assertEquals(listOf("Mango", "Zebra", "apple"), sortSongs(songs, SongSortField.ARTIST, false).map { it.title })
        assertEquals(listOf("apple", "Zebra", "Mango"), sortSongs(songs, SongSortField.ARTIST, true).map { it.title })
        // Alpha, Bravo, The Charlie (under C)
        assertEquals(listOf("apple", "Zebra", "Mango"), sortSongs(songs, SongSortField.ALBUM, false).map { it.title })
    }

    @Test
    fun `sorting by album keeps each album in track order, even Z to A`() {
        val songs = listOf(
            song("Two", album = "A", track = 2),
            song("One", album = "A", track = 1),
            song("Solo", album = "B", track = 1),
        )
        assertEquals(listOf("Solo", "One", "Two"), sortSongs(songs, SongSortField.ALBUM, true).map { it.title })
    }

    @Test
    fun `albums and artists sort both ways`() {
        val albums = groupAlbums(
            listOf(
                song("1", albumArtist = "The Corner Room", album = "Zeal"),
                song("2", albumArtist = "Keith Green", album = "An Apple"),
                song("3", albumArtist = "Brian", album = "Mid"),
            ),
        )
        assertEquals(listOf("An Apple", "Mid", "Zeal"), sortAlbums(albums, AlbumSortField.ALBUM, false).map { it.title })
        assertEquals(listOf("Zeal", "Mid", "An Apple"), sortAlbums(albums, AlbumSortField.ALBUM, true).map { it.title })
        assertEquals(listOf("Mid", "Zeal", "An Apple"), sortAlbums(albums, AlbumSortField.ARTIST, false).map { it.title })

        val artists = groupArtists(albums)
        assertEquals(listOf("Brian", "The Corner Room", "Keith Green"), sortArtists(artists, false).map { it.name })
        assertEquals(listOf("Keith Green", "The Corner Room", "Brian"), sortArtists(artists, true).map { it.name })
    }

    @Test
    fun `an unknown saved sort field falls back to the default`() {
        assertEquals(SongSortField.ARTIST, ListSort("ARTIST").fieldOr(SongSortField.TITLE))
        assertEquals(SongSortField.TITLE, ListSort("NONSENSE").fieldOr(SongSortField.TITLE))
        assertEquals(SongSortField.TITLE, ListSort().fieldOr(SongSortField.TITLE))
    }
}
