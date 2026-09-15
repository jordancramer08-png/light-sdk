package com.thelightphone.bible.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Trimmed from the real reading_plan_2026.json: a plain day, a Psalm 119 day (verse
// bounds present), and a PLAN COMPLETE day (empty passages), plus a couple of
// canonicalBookOrder/bookAliases entries covering the Song of Songs / Psalms cases
// called out in CLAUDE.md 8.
private const val SAMPLE_JSON = """
{
  "name": "One Story That Leads to Jesus",
  "year": 2026,
  "source": "BibleProject Annual Reading Plan 2026 v2",
  "totalReadingDays": 358,
  "days": [
    {
      "day": 1, "date": "2026-01-01", "weekday": "Thursday", "displayDate": "Jan 1",
      "complete": false, "label": "Genesis 1-3 • Psalms 1",
      "passages": [
        { "book": "Genesis", "startChapter": 1, "endChapter": 3 },
        { "book": "Psalms", "startChapter": 1, "endChapter": 1 }
      ]
    },
    {
      "day": 120, "date": "2026-04-30", "weekday": "Thursday", "displayDate": "Apr 30",
      "complete": false, "label": "Isaiah 45-48 • Psalms 119:33-64",
      "passages": [
        { "book": "Isaiah", "startChapter": 45, "endChapter": 48 },
        { "book": "Psalms", "startChapter": 119, "endChapter": 119, "startVerse": 33, "endVerse": 64 }
      ]
    },
    {
      "day": 365, "date": "2026-12-31", "weekday": "Thursday", "displayDate": "Dec 31",
      "complete": true, "label": "PLAN COMPLETE", "passages": []
    }
  ],
  "bookAliases": {
    "Genesis": ["Gen", "Ge", "Gn"],
    "Psalms": ["Psalm", "Pss", "Ps", "Psa"],
    "Song of Songs": ["Song of Solomon", "The Song of Solomon", "SoS", "Cant"]
  },
  "canonicalBookOrder": ["Genesis", "Psalms", "Song of Songs", "Isaiah"]
}
"""

class ReadingPlanTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val plan = json.decodeFromString(ReadingPlan.serializer(), SAMPLE_JSON)

    @Test
    fun `parses top-level fields`() {
        assertEquals("One Story That Leads to Jesus", plan.name)
        assertEquals(2026, plan.year)
        assertEquals(358, plan.totalReadingDays)
        assertEquals(3, plan.days.size)
        assertEquals(4, plan.canonicalBookOrder.size)
    }

    @Test
    fun `plain day has no verse bounds`() {
        val day = plan.days[0]
        assertEquals("Genesis 1-3 • Psalms 1", day.label)
        assertNull(day.passages[0].startVerse)
        assertNull(day.passages[0].endVerse)
    }

    @Test
    fun `psalm 119 day carries verse bounds on only the psalm passage`() {
        val day = plan.days[1]
        val isaiah = day.passages[0]
        val psalm119 = day.passages[1]

        assertNull(isaiah.startVerse)
        assertEquals(119, psalm119.startChapter)
        assertEquals(119, psalm119.endChapter)
        assertEquals(33, psalm119.startVerse)
        assertEquals(64, psalm119.endVerse)
    }

    @Test
    fun `plan complete day has no passages`() {
        val day = plan.days[2]
        assertTrue(day.complete)
        assertEquals("PLAN COMPLETE", day.label)
        assertTrue(day.passages.isEmpty())
    }
}
