package com.thelightphone.adventure

import com.thelightphone.adventure.engine.ZColor
import com.thelightphone.adventure.engine.ZStatusLine

/** Whether text came from the story or was typed by the player. */
enum class SpanKind { STORY, COMMAND }

/** Z-machine style bits, as set_text_style uses them. */
object ZStyle {
    const val REVERSE = 1
    const val BOLD = 2
    const val ITALIC = 4
    const val FIXED = 8
}

/** A run of text with one look. ZColor.DEFAULT means "use the theme's color". */
data class StorySpan(
    val text: String,
    val kind: SpanKind = SpanKind.STORY,
    val foreground: ZColor = ZColor.DEFAULT,
    val background: ZColor = ZColor.DEFAULT,
    val style: Int = 0,
)

/** One paragraph of the story, or one row of the upper window. */
typealias TextLine = List<StorySpan>

/** Everything the screen needs to draw. It holds no engine objects, only plain values. */
data class GameUiState(
    val lines: List<TextLine> = emptyList(),
    val status: ZStatusLine? = null,
    val upperRows: List<TextLine> = emptyList(),
    val waitingForInput: Boolean = false,
    val commandsTaken: Int = 0,
    val finished: Boolean = false,
)

/** The right side of the status bar. */
fun statusSummary(status: ZStatusLine): String =
    if (status.isTime) {
        "Time: %d:%02d".format(status.first, status.second)
    } else {
        "Score: ${status.first}  Moves: ${status.second}"
    }

/** The story so far, as lines of styled spans. Old lines drop off after [MAX_LINES]. */
class StoryLog {
    private val lines = ArrayList<ArrayList<StorySpan>>()

    fun append(text: String, look: StorySpan) {
        text.split('\n').forEachIndexed { index, part ->
            if (index > 0 || lines.isEmpty()) startLine()
            if (part.isNotEmpty()) addToLastLine(look.copy(text = part))
        }
    }

    private fun startLine() {
        lines += ArrayList<StorySpan>()
        if (lines.size > MAX_LINES) lines.removeAt(0)
    }

    private fun addToLastLine(span: StorySpan) {
        val line = lines.last()
        val previous = line.lastOrNull()
        if (previous != null && previous.copy(text = "") == span.copy(text = "")) {
            line[line.size - 1] = previous.copy(text = previous.text + span.text)
        } else {
            line += span
        }
    }

    fun clear() = lines.clear()

    fun snapshot(): List<TextLine> = lines.map { it.toList() }

    private companion object {
        const val MAX_LINES = 1500
    }
}

/** The upper window: a fixed grid of characters, each with its own look. */
class UpperWindow(private val width: Int) {
    private var rows = ArrayList<Array<StorySpan?>>()

    var line = 1
        private set
    var column = 1
        private set

    fun resize(lineCount: Int) {
        while (rows.size > lineCount) rows.removeAt(rows.size - 1)
        while (rows.size < lineCount) rows += arrayOfNulls<StorySpan>(width)
    }

    fun moveCursor(newLine: Int, newColumn: Int) {
        line = newLine
        column = newColumn
    }

    fun write(text: String, look: StorySpan) {
        for (char in text) {
            if (char == '\n') moveCursor(line + 1, 1) else put(char, look)
        }
    }

    private fun put(char: Char, look: StorySpan) {
        val row = rows.getOrNull(line - 1)
        if (row != null && column in 1..width) row[column - 1] = look.copy(text = char.toString())
        column++
    }

    fun clear() {
        rows.forEach { it.fill(null) }
        moveCursor(1, 1)
    }

    fun eraseToEndOfLine() {
        val row = rows.getOrNull(line - 1) ?: return
        for (index in (column - 1).coerceAtLeast(0) until width) row[index] = null
    }

    /** Each row as spans, with blanks kept so columns line up. */
    fun snapshot(): List<TextLine> = rows.map(::rowSpans)

    private fun rowSpans(row: Array<StorySpan?>): TextLine {
        val log = StoryLog()
        row.forEach { cell -> log.append(cell?.text ?: " ", cell ?: StorySpan("")) }
        return log.snapshot().firstOrNull() ?: emptyList()
    }
}
