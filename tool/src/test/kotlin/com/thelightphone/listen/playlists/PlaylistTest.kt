package com.thelightphone.listen.playlists

import com.thelightphone.listen.music.MusicLibraryState
import com.thelightphone.listen.music.Song
import com.thelightphone.listen.music.groupAlbums
import com.thelightphone.listen.music.groupArtists
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistTest {

    private fun song(
        title: String,
        artist: String = "Artist",
        albumArtist: String = artist,
        album: String = "Album",
        track: Int = 0,
        year: Int = 0,
        path: String = "$albumArtist/$album/$title.mp3",
    ) = Song(path, 1, 1, title, artist, albumArtist, album, track, 0, year, 1000)

    private fun library(vararg songs: Song): MusicLibraryState {
        val albums = groupAlbums(songs.toList())
        return MusicLibraryState(songs.toList(), albums, groupArtists(albums), loaded = true)
    }

    private val shane2015 = listOf(
        song("Psalm 34", artist = "Shane & Shane", album = "The Worship Initiative", track = 2, year = 2015),
        song("Though You Slay Me", artist = "Shane & Shane", album = "The Worship Initiative", track = 1, year = 2015),
    )
    private val shane2010 = song("Embrace", artist = "Shane & Shane", album = "Everything Is Different", track = 1, year = 2010)
    private val guest = song("Guest Spot", artist = "Shane & Shane", albumArtist = "Various", album = "Compilation", track = 4)
    private val keith = song("Rushing Wind", artist = "Keith Green", album = "No Compromise", track = 3)

    @Test
    fun `an album entry plays in track order`() {
        val lib = library(*shane2015.toTypedArray())
        val songs = matchEntry(PlaylistEntry.album("shane & SHANE", "the worship initiative"), lib).songs
        assertEquals(listOf("Though You Slay Me", "Psalm 34"), songs.map { it.title })
    }

    @Test
    fun `an artist entry goes album by album by year, and includes songs they perform on other albums`() {
        val lib = library(*(shane2015 + shane2010 + guest + keith).toTypedArray())
        val match = matchEntry(PlaylistEntry.artist("Shane & Shane"), lib)
        assertEquals(listOf("Embrace", "Though You Slay Me", "Psalm 34", "Guest Spot"), match.songs.map { it.title })
        assertEquals(3, match.albums.size)
    }

    @Test
    fun `an artist entry picks up music sent later`() {
        val entry = PlaylistEntry.artist("Shane & Shane")
        assertEquals(2, matchEntry(entry, library(*shane2015.toTypedArray())).songs.size)
        assertEquals(3, matchEntry(entry, library(*(shane2015 + shane2010).toTypedArray())).songs.size)
    }

    @Test
    fun `entries that match nothing are kept and marked not on phone`() {
        val playlist = Playlist(
            "p", "Mix",
            listOf(PlaylistEntry.album("Gone", "Removed"), PlaylistEntry.song(keith.path), PlaylistEntry.song("A/B/03 Old Song.mp3")),
        )
        val matches = matchEntries(playlist, library(keith))
        assertEquals(listOf(false, true, false), matches.map { it.onPhone })
        assertEquals("Old Song", entryTitle(matches[2]))
        assertEquals("Song · A", entryKindLine(matches[2]))
        assertEquals(listOf("Rushing Wind"), playlistSongs(matches).map { it.title })
    }

    @Test
    fun `a song matched by two entries plays once, the first time`() {
        val lib = library(*(shane2015 + keith).toTypedArray())
        val playlist = Playlist(
            "p", "Mix",
            listOf(
                PlaylistEntry.song(shane2015[0].path),
                PlaylistEntry.song(keith.path),
                PlaylistEntry.album("Shane & Shane", "The Worship Initiative"),
            ),
        )
        assertEquals(listOf("Psalm 34", "Rushing Wind", "Though You Slay Me"), playlistSongs(playlist, lib).map { it.title })
    }

    @Test
    fun `adding the same music twice does nothing, and remove and move work`() {
        val a = PlaylistEntry.artist("Keith Green")
        val b = PlaylistEntry.album("Shane & Shane", "Hymns")
        val c = PlaylistEntry.song("X/Y/z.mp3")
        var p = Playlist("p", "Mix").adding(a).adding(b).adding(c).adding(PlaylistEntry.artist("keith  green"))
        assertEquals(listOf(a, b, c), p.entries)
        assertTrue(p.contains(PlaylistEntry.artist("KEITH GREEN")))

        p = p.moving(2, up = true)
        assertEquals(listOf(a, c, b), p.entries)
        assertEquals(p, p.moving(0, up = true)) // already at the top
        assertEquals(p, p.moving(2, up = false)) // already at the bottom

        p = p.removingAt(0)
        assertEquals(listOf(c, b), p.entries)
        assertFalse(p.removing(b).contains(b))
    }

    @Test
    fun `playlists survive a save and load, and a broken file is kept aside instead of lost`() {
        val dir = Files.createTempDirectory("playlists").toFile()
        val file = File(dir, "playlists.json")
        val store = PlaylistsFile(file)
        val data = PlaylistsData(
            playlists = listOf(Playlist("id1", "Road trip", listOf(PlaylistEntry.artist("Keith Green"), PlaylistEntry.song("A/B/c.mp3")))),
        )
        store.save(data)
        assertEquals(data, store.load())
        assertFalse(File(dir, "playlists.json.tmp").exists())

        file.writeText("{ not json")
        assertEquals(PlaylistsData(), store.load())
        assertFalse(file.exists())
        assertEquals(1, dir.listFiles()!!.count { it.name.startsWith("playlists.broken-") })
    }

    @Test
    fun `an older or hand-edited file still loads`() {
        val dir = Files.createTempDirectory("playlists").toFile()
        val file = File(dir, "playlists.json")
        file.writeText("""{"playlists":[{"id":"x","name":"Old","entries":[{"kind":"artist","artist":"A"},{"kind":"future","extra":1}]}],"newField":true}""")
        val loaded = PlaylistsFile(file).load().playlists.single()
        assertEquals("Old", loaded.name)
        assertEquals(PlaylistEntry.artist("A"), loaded.entries[0])
        assertFalse(matchEntry(loaded.entries[1], library(keith)).onPhone)
    }

    @Test
    fun `typed names are tidied`() {
        assertEquals("Road Trip", cleanPlaylistName("  Road   Trip "))
        assertEquals(null, cleanPlaylistName("   "))
    }
}
