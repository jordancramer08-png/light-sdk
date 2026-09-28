package com.thelightphone.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import kotlin.math.hypot

/** A note marker on a page: its number ("12"), its note, and where it sits in the page's text. */
data class PageNote(val marker: String, val text: String, val start: Int, val end: Int)

/**
 * The note markers in [text] (a [styledChapterText], which carries them) between [start] and
 * [end] — one page of it. Their offsets are [text]'s own.
 */
fun pageNotes(text: AnnotatedString, start: Int = 0, end: Int = text.length): List<PageNote> =
    text.getStringAnnotations(NOTE_ANNOTATION, start, end).map {
        PageNote(text.text.substring(it.start, it.end), it.item, it.start, it.end)
    }

/**
 * The note whose marker is nearest the finger, if that marker's box is no more than [reachPx]
 * away (a finger is wider than a raised number); otherwise null, and the tap turns the page.
 */
fun nearestNote(tap: Offset, markers: List<Pair<PageNote, Rect>>, reachPx: Float): PageNote? =
    markers
        .map { (note, box) -> note to distance(tap, box) }
        .filter { (_, d) -> d <= reachPx }
        .minByOrNull { (_, d) -> d }
        ?.first

/** Where each marker is drawn: its characters' boxes, from the page's laid-out text. */
fun markerBoxes(layout: TextLayoutResult, notes: List<PageNote>): List<Pair<PageNote, Rect>> =
    notes.filter { it.end <= layout.layoutInput.text.length }.map { note ->
        note to (note.start until note.end).map { layout.getBoundingBox(it) }.reduce { a, b -> a.union(b) }
    }

private fun Rect.union(other: Rect) =
    Rect(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))

/** 0 inside the box, else the straight-line distance to its nearest edge. */
private fun distance(point: Offset, box: Rect): Float {
    val dx = maxOf(box.left - point.x, 0f, point.x - box.right)
    val dy = maxOf(box.top - point.y, 0f, point.y - box.bottom)
    return hypot(dx, dy)
}
