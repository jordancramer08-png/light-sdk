package com.thelightphone.sample.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val SAMPLE_JSON = """
{
  "title": "If This Is the End",
  "subtitle": "A Study of 1 and 2 Peter",
  "author": "Nathan Bayly",
  "lessonCount": 27,
  "note": "Personal study copy. Not for redistribution.",
  "lessons": [
    {
      "lesson": 17,
      "title": "Live in Light of the End",
      "passage": "4:7-11",
      "scriptureRef": "1 Peter 4:7-11",
      "scripture": "The end of all things is near; therefore...",
      "commentary": "",
      "items": [
        { "id": "L17Q1", "type": "question", "number": 1, "text": "When Peter says..." },
        { "id": "L17C1", "type": "childrens", "number": null, "text": "How and when do you pray?" }
      ],
      "footnotes": ["1 Peter 1:15-16"]
    },
    {
      "lesson": 1,
      "title": "Getting the Lay of the Land",
      "passage": "1 Peter 1-5",
      "scriptureRef": "",
      "scripture": "",
      "commentary": "Background on the letter.",
      "items": [],
      "footnotes": []
    }
  ]
}
"""

class StudyModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses book order, item types, and optional fields`() {
        val content = json.decodeFromString(StudyContent.serializer(), SAMPLE_JSON)

        assertEquals("If This Is the End", content.title)
        assertEquals(27, content.lessonCount)
        assertEquals(2, content.lessons.size)

        val lesson17 = content.lesson(17)!!
        assertEquals("Live in Light of the End", lesson17.title)
        assertEquals(listOf("L17Q1", "L17C1"), lesson17.items.map { it.id })
        assertEquals(ItemType.QUESTION, lesson17.items[0].type)
        assertEquals(1, lesson17.items[0].number)
        assertEquals(ItemType.CHILDRENS, lesson17.items[1].type)
        assertNull(lesson17.items[1].number)
        assertEquals(listOf("1 Peter 1:15-16"), lesson17.footnotes)
    }

    @Test
    fun `questions filters out childrens items`() {
        val content = json.decodeFromString(StudyContent.serializer(), SAMPLE_JSON)
        val lesson17 = content.lesson(17)!!

        assertEquals(listOf("L17Q1"), lesson17.questions.map { it.id })
    }

    @Test
    fun `notesId is stable per lesson`() {
        val content = json.decodeFromString(StudyContent.serializer(), SAMPLE_JSON)

        assertEquals("L17NOTES", content.lesson(17)!!.notesId)
        assertEquals("L1NOTES", content.lesson(1)!!.notesId)
    }

    @Test
    fun `empty scripture and commentary default to empty string, not omitted`() {
        val content = json.decodeFromString(StudyContent.serializer(), SAMPLE_JSON)
        val lesson1 = content.lesson(1)!!

        assertEquals("", lesson1.scripture)
        assertEquals("Background on the letter.", lesson1.commentary)
    }

    @Test
    fun `unknown lesson number returns null`() {
        val content = json.decodeFromString(StudyContent.serializer(), SAMPLE_JSON)

        assertNull(content.lesson(99))
    }
}
