package com.thelightphone.adventure

import kotlin.test.Test
import kotlin.test.assertEquals

/** The saved story text used by the automatic save. */
class GameTextTest {
    private fun read(text: String): List<TextLine> = StoryLog().also { appendSavedStoryText(it, text) }.snapshot()

    @Test
    fun savedTextKeepsCommandsApart() {
        val lines = listOf(
            listOf(StorySpan(">"), StorySpan("look", kind = SpanKind.COMMAND)),
            listOf(StorySpan("West of House")),
        )
        assertEquals(
            listOf(
                listOf(StorySpan(">"), StorySpan("look", kind = SpanKind.COMMAND)),
                listOf(StorySpan("West of House")),
            ),
            read(savedStoryText(lines)),
        )
    }

    @Test
    fun oldSavedTextWithNoMarksIsAllStory() {
        assertEquals(listOf(listOf(StorySpan(">look")), listOf(StorySpan("West of House"))), read(">look\nWest of House"))
    }
}
