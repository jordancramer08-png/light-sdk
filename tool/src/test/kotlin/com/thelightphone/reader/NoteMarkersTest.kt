package com.thelightphone.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.thelightphone.reader.epub.StyleRange
import com.thelightphone.reader.epub.TextStyleKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoteMarkersTest {

    private val first = PageNote("1", "First note.", 5, 6)
    private val second = PageNote("2", "Second note.", 20, 21)

    // Two markers, 20 px square, on the same line.
    private val markers = listOf(
        first to Rect(100f, 0f, 120f, 20f),
        second to Rect(300f, 0f, 320f, 20f),
    )

    @Test
    fun tapOnAMarkerOpensItsNote() {
        assertEquals(first, nearestNote(Offset(110f, 10f), markers, reachPx = 30f))
    }

    @Test
    fun tapJustBesideAMarkerStillOpensIt() {
        assertEquals(second, nearestNote(Offset(340f, 10f), markers, reachPx = 30f))
    }

    @Test
    fun tapFarFromEveryMarkerTurnsThePage() {
        assertNull(nearestNote(Offset(200f, 10f), markers, reachPx = 30f))
        assertNull(nearestNote(Offset(110f, 200f), markers, reachPx = 30f))
    }

    @Test
    fun theNearerOfTwoMarkersWins() {
        assertEquals(second, nearestNote(Offset(290f, 10f), markers, reachPx = 200f))
    }

    @Test
    fun aPageCutFromTheChapterKeepsItsNotes() {
        val text = "One.1 Two.2 Three."
        val chapter = styledChapterText(
            text,
            listOf(
                StyleRange(TextStyleKind.NOTE, 4, 5, note = "Note one."),
                StyleRange(TextStyleKind.NOTE, 10, 11, note = "Note two."),
            ),
        )

        val page = chapter.subSequence(6, text.length) // starts after the first marker

        assertEquals(listOf(PageNote("2", "Note two.", 4, 5)), pageNotes(page))
    }
}
