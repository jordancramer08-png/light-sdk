package com.jordan.watch.logic

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaJsonTest {

    private val root: File = Files.createTempDirectory("watch-media").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun folder(path: String): File = File(root, path).apply { mkdirs() }

    @Test
    fun `full movie json is read`() {
        val dir = folder("Movies/_Singles/Hacksaw Ridge (2016)")
        File(dir, "movie.json").writeText(
            """
            { "schema": 1, "id": "_Singles/Hacksaw Ridge (2016)", "title": "Hacksaw Ridge", "year": 2016,
              "collection": null, "collectionNumber": null, "overview": "WWII medic.",
              "genres": ["Drama","War"], "runtimeMin": 139, "rating": 8.1, "contentRating": "R",
              "director": "Mel Gibson", "cast": ["Andrew Garfield"], "poster": "poster.jpg",
              "backdrop": null, "file": "movie.mp4", "width": 1920, "height": 1080,
              "videoCodec": "h264", "audioCodec": "aac", "bytes": 123, "sentAt": "2026-10-12T15:00:00",
              "somethingNew": 42 }
            """.trimIndent()
        )
        val movie = MediaJson.readMovie(dir, "_Singles/Hacksaw Ridge (2016)")
        assertEquals("Hacksaw Ridge", movie.title)
        assertEquals(139, movie.runtimeMin)
        assertEquals(listOf("Drama", "War"), movie.genres)
        assertNull(movie.collectionNumber)
    }

    @Test
    fun `missing movie json falls back to folder name`() {
        val dir = folder("Movies/Harry Potter/Harry Potter and the Goblet of Fire (2005)")
        val movie = MediaJson.readMovie(dir, "Harry Potter/Harry Potter and the Goblet of Fire (2005)")
        assertEquals("Harry Potter and the Goblet of Fire", movie.title)
        assertEquals(2005, movie.year)
        assertEquals("Harry Potter", movie.collection)
    }

    @Test
    fun `broken movie json falls back and singles have no collection`() {
        val dir = folder("Movies/_Singles/Up (2009)")
        File(dir, "movie.json").writeText("{ \"title\": ")
        val movie = MediaJson.readMovie(dir, "_Singles/Up (2009)")
        assertEquals("Up", movie.title)
        assertEquals(2009, movie.year)
        assertNull(movie.collection)
    }

    @Test
    fun `json missing required title falls back`() {
        val dir = folder("Movies/_Singles/Heat (1995)")
        File(dir, "movie.json").writeText("""{ "id": "_Singles/Heat (1995)" }""")
        assertEquals("Heat", MediaJson.readMovie(dir, "_Singles/Heat (1995)").title)
    }

    @Test
    fun `folder without a year keeps the whole name`() {
        assertEquals("Some Film" to null, MediaJson.splitTitleYear("Some Film"))
        assertEquals("1917" to 2019, MediaJson.splitTitleYear("1917 (2019)"))
    }

    @Test
    fun `show json and fallback`() {
        val good = folder("TV/The Office")
        File(good, "show.json").writeText("""{ "id": "The Office", "title": "The Office (US)", "year": 2005 }""")
        assertEquals("The Office (US)", MediaJson.readShow(good).title)

        val bare = folder("TV/Chosen (2017)")
        val show = MediaJson.readShow(bare)
        assertEquals("Chosen", show.title)
        assertEquals("Chosen (2017)", show.id)
    }

    @Test
    fun `episode json and fallback from file name`() {
        val season = folder("TV/The Office/Season 02")
        val json = File(season, "S02E01.json")
        json.writeText("""{ "id": "The Office/S02E01", "title": "The Dundies", "season": 2, "episode": 1, "runtimeMin": 22 }""")
        assertEquals("The Dundies", MediaJson.readEpisode(json, "The Office").title)

        val missing = MediaJson.readEpisode(File(season, "S02E10.json"), "The Office")
        assertEquals(2, missing.season)
        assertEquals(10, missing.episode)
        assertEquals("Episode 10", missing.title)
        assertEquals("The Office/S02E10", missing.id)

        File(season, "S02E11.json").writeText("garbage")
        assertEquals(11, MediaJson.readEpisode(File(season, "S02E11.json"), "The Office").episode)
    }
}
