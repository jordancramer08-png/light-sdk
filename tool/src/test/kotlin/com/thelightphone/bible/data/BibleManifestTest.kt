package com.thelightphone.bible.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

private const val SAMPLE_JSON = """
{
  "displayName": "English Standard Version (2007)",
  "books": {
    "Genesis": { "name": "Genesis", "slug": "genesis", "chapterCount": 50 },
    "Song of Solomon": { "name": "Song of Solomon", "slug": "song-of-solomon", "chapterCount": 8 }
  }
}
"""

class BibleManifestTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses display name and books keyed by name`() {
        val manifest = json.decodeFromString(BibleManifest.serializer(), SAMPLE_JSON)

        assertEquals("English Standard Version (2007)", manifest.displayName)
        assertEquals(2, manifest.books.size)
        assertEquals("genesis", manifest.books.getValue("Genesis").slug)
        assertEquals(50, manifest.books.getValue("Genesis").chapterCount)
        assertEquals("song-of-solomon", manifest.books.getValue("Song of Solomon").slug)
    }
}
