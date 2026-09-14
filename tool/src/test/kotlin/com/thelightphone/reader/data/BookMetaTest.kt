package com.thelightphone.reader.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

private const val SAMPLE_JSON = """
{
  "slug": "the-hobbit",
  "title": "The Hobbit",
  "author": "J.R.R. Tolkien",
  "chapters": [
    { "index": 1, "title": "An Unexpected Party", "file": "001.txt", "chars": 28451 },
    { "index": 2, "title": "Chapter 2", "file": "002.txt", "chars": 19032 }
  ]
}
"""

class BookMetaTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses book fields and chapters in order`() {
        val meta = json.decodeFromString(BookMeta.serializer(), SAMPLE_JSON)

        assertEquals("the-hobbit", meta.slug)
        assertEquals("The Hobbit", meta.title)
        assertEquals("J.R.R. Tolkien", meta.author)
        assertEquals(2, meta.chapters.size)
        assertEquals("An Unexpected Party", meta.chapters[0].title)
        assertEquals("001.txt", meta.chapters[0].file)
        assertEquals(28451, meta.chapters[0].chars)
        assertEquals("Chapter 2", meta.chapters[1].title)
    }
}
