package com.thelightphone.listen

import com.thelightphone.listen.books.Book
import com.thelightphone.listen.books.BookFile
import com.thelightphone.listen.books.BookPosition
import com.thelightphone.listen.books.bookPositionMs
import com.thelightphone.listen.books.chapterIndexAt
import com.thelightphone.listen.books.chapterSpotText
import com.thelightphone.listen.books.chaptersOf
import com.thelightphone.listen.books.locate
import com.thelightphone.listen.books.parseBookJson
import com.thelightphone.listen.music.MusicLibraryState
import com.thelightphone.listen.music.Song
import com.thelightphone.listen.music.SongSortField
import com.thelightphone.listen.music.groupAlbums
import com.thelightphone.listen.music.groupArtists
import com.thelightphone.listen.music.sortSongs
import com.thelightphone.listen.music.sortedByTitle
import com.thelightphone.listen.playback.MusicState
import com.thelightphone.listen.playback.MusicStateStore
import com.thelightphone.listen.playlists.Playlist
import com.thelightphone.listen.playlists.PlaylistEntry
import com.thelightphone.listen.playlists.playlistSongs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The speed check for Session 7: a library the size of Jordan's (1,400 songs in 270 albums)
 * and the biggest audiobook (560 files), through everything that runs when a screen opens or
 * while playing. The limits are loose (a laptop is faster than the phone); the printed times
 * are what to look at. None of this runs on the main thread in the app.
 */
class SpeedCheckTest {

    private fun songs(): List<Song> = (0 until SONGS).map { i ->
        val album = i % ALBUMS
        val artist = album % 90
        Song(
            path = "Artist $artist/Album $album/${i / ALBUMS + 1} Song $i.mp3",
            size = 8_000_000,
            modified = 1,
            title = if (i % 7 == 0) "The Song Número $i" else "Song $i",
            // Some albums have guest performers, like a soundtrack with two composers.
            artist = if (i % 11 == 0) "Guest Artíst ${i % 30}" else "Artist $artist",
            albumArtist = "Artist $artist",
            album = "Album $album",
            track = i / ALBUMS + 1,
            year = 1990 + album % 30,
            durationMs = 240_000,
        )
    }

    private fun library(songs: List<Song>): MusicLibraryState {
        val sorted = sortedByTitle(songs)
        val albums = groupAlbums(sorted)
        return MusicLibraryState(sorted, albums, groupArtists(albums), loaded = true)
    }

    private fun bigBook() = Book(
        id = "Author/Big Book",
        folder = "Author/Big Book",
        title = "Big Book",
        author = "Author",
        files = (1..BOOK_FILES).map { BookFile("$it.mp3", "Chapter $it", 2_000_000, 1, durationMs = 95_000) },
    )

    private fun timed(label: String, limitMs: Long, block: () -> Unit) {
        block() // warm up the JVM once, like a second visit to the screen
        val start = System.nanoTime()
        block()
        val ms = (System.nanoTime() - start) / 1_000_000
        println("SPEED  %-48s %5d ms".format(label, ms))
        assertTrue(ms < limitMs, "$label took $ms ms (limit $limitMs)")
    }

    @Test
    fun `a 1,400 song library sorts, groups and expands quickly`() {
        val songs = songs()
        lateinit var lib: MusicLibraryState
        timed("library: sort + group 1,400 songs", 1_500) { lib = library(songs) }
        assertEquals(ALBUMS, lib.albums.size)

        for (field in SongSortField.entries) {
            timed("songs list sorted by ${field.label}", 1_000) { sortSongs(lib.songs, field, descending = true) }
        }

        // A playlist of 20 artists, 20 albums and 50 songs, expanded as the Playlists screen does.
        val playlist = Playlist(
            id = "p",
            name = "Big mix",
            entries = (0 until 20).map { PlaylistEntry.artist("Artist $it") } +
                lib.albums.take(20).map { PlaylistEntry.album(it.artist, it.title) } +
                lib.songs.take(50).map { PlaylistEntry.song(it.path) },
        )
        timed("playlist with 90 entries expanded", 1_000) { playlistSongs(playlist, lib) }
        val ten = List(10) { playlist.copy(id = "p$it") }
        timed("Playlists screen: 10 such playlists counted", 3_000) { ten.forEach { playlistSongs(it, lib) } }

        val state = MusicState(paths = lib.songs.map { it.path }, index = 700, positionMs = 61_000)
        timed("music_state.json with 1,400 songs encoded", 500) { MusicStateStore.encode(state) }
    }

    @Test
    fun `a 560 file audiobook stays quick while playing`() {
        val book = bigBook()
        lateinit var chapters: List<com.thelightphone.listen.books.BookChapter>
        timed("book: 560 chapters built", 500) { chapters = chaptersOf(book) }
        assertEquals(BOOK_FILES, chapters.size)

        // These run 4 times a second while the book plays (seek bar, "Chapter N of M" line).
        val here = BookPosition(fileIndex = 559, positionMs = 50_000)
        timed("book: 1,000 ticks of chapter + place lookups", 1_500) {
            repeat(1_000) {
                chapterIndexAt(chapters, here)
                bookPositionMs(book, here)
            }
        }
        timed("book: 1,000 skips across files", 1_500) { repeat(1_000) { locate(book, 53_000_000) } }
        assertEquals("Chapter 560 of 560, 0:45 left in chapter", chapterSpotText(chapters, here))

        val json = buildString {
            append("""{"schema":1,"id":"Author/Big Book","title":"Big Book","author":"Author","files":[""")
            append((1..BOOK_FILES).joinToString(",") { """{"name":"$it.mp3","label":"Chapter $it","bytes":2000000}""" })
            append("]}")
        }
        timed("book.json with 560 files parsed", 500) { parseBookJson(json) }
        assertEquals(BOOK_FILES, parseBookJson(json)?.files?.size)
    }

    private companion object {
        const val SONGS = 1_400
        const val ALBUMS = 270
        const val BOOK_FILES = 560
    }
}
