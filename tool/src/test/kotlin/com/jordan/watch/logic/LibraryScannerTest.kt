package com.jordan.watch.logic

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds a pretend /sdcard/Watch in a temp folder and scans it. */
class LibraryScannerTest {

    private val root: File = Files.createTempDirectory("watch-root").toFile()
    private var movieReads = 0
    private var episodeReads = 0

    private val scanner = LibraryScanner(
        root,
        readMovie = { folder, id -> movieReads++; MediaJson.readMovie(folder, id) },
        readEpisode = { json, show -> episodeReads++; MediaJson.readEpisode(json, show) },
    )

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun file(path: String, text: String = "x"): File =
        File(root, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun movieJson(id: String, title: String, number: Int? = null, file: String = "movie.mp4") =
        """{"id":"$id","title":"$title","collection":"Harry Potter","collectionNumber":$number,"file":"$file"}"""

    private fun buildLibrary() {
        file("Movies/_Singles/Up (2009)/movie.mp4")
        file("Movies/_Singles/Up (2009)/poster.jpg")
        file("Movies/Harry Potter/02 Chamber (2002)/movie.mkv")
        file("Movies/Harry Potter/02 Chamber (2002)/movie.json", movieJson("Harry Potter/02 Chamber (2002)", "Chamber", 2, "movie.mkv"))
        file("Movies/Harry Potter/01 Stone (2001)/movie.mp4")
        file("Movies/Harry Potter/01 Stone (2001)/movie.json", movieJson("Harry Potter/01 Stone (2001)", "Stone", 1))
        file("TV/Bluey/show.json", """{"id":"Bluey","title":"Bluey"}""")
        file("TV/Bluey/Season 01/S01E02.mp4")
        file("TV/Bluey/Season 01/S01E01.mp4")
        file("TV/Bluey/Season 01/S01E01.json", """{"id":"Bluey/S01E01","title":"Magic Xylophone","season":1,"episode":1}""")
        file("TV/Bluey/Season 02/S02E01.mkv")
    }

    @Test
    fun `finds movies, collections and shows`() {
        buildLibrary()
        val library = Library(scanner.scan(LibraryIndex()))

        assertEquals(3, library.movies.size)
        val up = assertNotNull(library.movie("_Singles/Up (2009)"))
        assertEquals("Up", up.movie.title)
        assertEquals(2009, up.movie.year)
        assertNull(up.movie.collection)
        assertEquals("Movies/_Singles/Up (2009)", up.folder)
        assertEquals("movie.mp4", up.video)

        val potter = library.collections.single()
        assertEquals("Harry Potter", potter.name)
        assertEquals(listOf("Stone", "Chamber"), potter.movies.map { it.movie.title })
        assertEquals("movie.mkv", library.movie("Harry Potter/02 Chamber (2002)")!!.video)

        val bluey = assertNotNull(library.show("Bluey"))
        assertEquals(listOf("S01E01.mp4", "S01E02.mp4", "S02E01.mkv"), bluey.episodes.map { it.video })
        assertEquals("Magic Xylophone", bluey.episodes[0].episode.title)
        assertEquals(listOf(1, 2), library.seasons(bluey))
        assertEquals(2, library.episodesOf(bluey, 1).size)
        assertEquals("Bluey", library.episode("Bluey/S02E01")!!.first.show.id)
    }

    @Test
    fun `skips folders without a video, hidden folders and half-sent files`() {
        buildLibrary()
        file("Movies/_Singles/Empty (2000)/poster.jpg")
        file("Movies/_Singles/Sending (2001)/.movie.mp4.part")
        file("Movies/_Singles/.hidden (2002)/movie.mp4")
        file("TV/No Episodes/show.json", """{"id":"No Episodes","title":"No Episodes"}""")
        file("Movies/_Singles/Up (2009)/notes.txt")

        val index = scanner.scan(LibraryIndex())
        assertEquals(3, index.movies.size)
        assertEquals(listOf("Bluey"), index.shows.map { it.show.id })
    }

    @Test
    fun `broken or missing json falls back to the folder name`() {
        file("Movies/_Singles/Hacksaw Ridge (2016)/movie.mp4")
        file("Movies/_Singles/Hacksaw Ridge (2016)/movie.json", "{ broken")
        file("TV/The Office/Season 01/S01E03.mp4")

        val library = Library(scanner.scan(LibraryIndex()))
        val movie = library.movies.single().movie
        assertEquals("Hacksaw Ridge", movie.title)
        assertEquals(2016, movie.year)
        val episode = library.shows.single().episodes.single().episode
        assertEquals("The Office/S01E03", episode.id)
        assertEquals(1 to 3, episode.season to episode.episode)
    }

    @Test
    fun `missing folders give an empty library, never a crash`() {
        assertTrue(Library(scanner.scan(LibraryIndex())).isEmpty)
        root.deleteRecursively()
        assertTrue(Library(scanner.scan(LibraryIndex())).isEmpty)
    }

    @Test
    fun `a rescan re-reads only what changed`() {
        buildLibrary()
        val first = scanner.scan(LibraryIndex())
        assertEquals(3, movieReads)
        assertEquals(3, episodeReads)

        // Nothing changed: nothing is read again.
        val same = scanner.scan(first)
        assertEquals(3, movieReads)
        assertEquals(3, episodeReads)
        assertEquals(first.movies, same.movies)

        // One movie's json changes; one episode is added.
        val json = file("Movies/Harry Potter/01 Stone (2001)/movie.json",
            movieJson("Harry Potter/01 Stone (2001)", "Philosopher's Stone", 1))
        json.setLastModified(json.lastModified() + 5_000)
        file("TV/Bluey/Season 02/S02E02.mp4")

        val after = Library(scanner.scan(same))
        assertEquals(4, movieReads)
        assertEquals(4, episodeReads)
        assertEquals("Philosopher's Stone", after.movie("Harry Potter/01 Stone (2001)")!!.movie.title)
        assertEquals(4, after.show("Bluey")!!.episodes.size)
    }

    @Test
    fun `removed titles drop out`() {
        buildLibrary()
        val first = scanner.scan(LibraryIndex())
        File(root, "Movies/_Singles/Up (2009)").deleteRecursively()
        File(root, "TV/Bluey/Season 02").deleteRecursively()

        val after = Library(scanner.scan(first))
        assertNull(after.movie("_Singles/Up (2009)"))
        assertEquals(2, after.show("Bluey")!!.episodes.size)
    }

    @Test
    fun `stamp changes with last-sync and with folders, not otherwise`() {
        buildLibrary()
        val stamp = scanner.stamp()
        assertEquals(stamp, scanner.stamp())

        file(".state/last-sync.txt", "2026-10-12T15:00:00")
        val synced = scanner.stamp()
        assertNotEquals(stamp, synced)

        file("Movies/_Singles/Coco (2017)/movie.mp4")
        assertNotEquals(synced, scanner.stamp())
    }

    @Test
    fun `the index survives saving and loading`() {
        buildLibrary()
        val index = scanner.scan(LibraryIndex())
        val store = LibraryIndexStore(File(root, "cache/library_index.json"))
        store.save(index)
        assertEquals(index, store.load())

        File(root, "cache/library_index.json").writeText("""{"version":0}""")
        assertEquals(LibraryIndex(), store.load())
    }
}
