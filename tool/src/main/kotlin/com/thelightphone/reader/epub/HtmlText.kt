package com.thelightphone.reader.epub

import kotlinx.serialization.Serializable

// Tags whose start or end marks a paragraph boundary when flattening HTML to text.
private val BLOCK_BREAK_TAGS = setOf(
    "p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6",
    "blockquote", "section", "article", "header", "footer", "tr",
)
private val SKIPPED_CONTENT_TAGS = setOf("script", "style")
private val ITALIC_TAGS = setOf("i", "em", "cite")
private val BOLD_TAGS = setOf("b", "strong")
private val RAISED_TAGS = setOf("sup", "small")

// Footnotes set beside the text in print. They open from their markers instead, so their
// text is left out of the chapter. Endnote lists in a Notes chapter are kept: only an <aside>
// marked as a note, or anything marked as a footnote, is hidden.
private val ASIDE_NOTE_TYPES = setOf(
    "footnote", "footnotes", "endnote", "endnotes", "rearnote", "rearnotes", "note",
    "doc-footnote", "doc-endnote",
)
private val FOOTNOTE_TYPES = setOf("footnote", "footnotes", "doc-footnote")
private val NOTEREF_TYPES = setOf("noteref", "doc-noteref")
private val BACKLINK_TYPES = setOf("backlink", "doc-backlink")

/** A link in `<sup>` or `<small>` is a note marker only when it's this short ("12", "[*]", "†"). */
const val MAX_MARKER_CHARS = 6

/** What a stretch of chapter text looks like, besides plain. */
@Serializable
enum class TextStyleKind {
    ITALIC,
    BOLD,

    /** One or more paragraphs from a `<blockquote>`, drawn indented. */
    QUOTE,

    /** A scene break: its own paragraph, always "⁂", drawn centered. */
    SCENE_BREAK,

    /** A footnote or endnote marker ("12"), drawn small and raised; its range's [StyleRange.note] is the note. */
    NOTE,
}

/**
 * [style] covers the characters from [start] up to (not including) [end]. A [TextStyleKind.NOTE]
 * range also carries the text of the note its marker points to.
 */
@Serializable
data class StyleRange(val style: TextStyleKind, val start: Int, val end: Int, val note: String? = null)

/**
 * A note marker found in the text, not yet matched to its note: the marker covers [start] up to
 * [end], and its link points at [href] ("notes.xhtml#n12", "#fn3"). [explicit] means the book
 * marked it as a note reference itself (`epub:type="noteref"`, `role="doc-noteref"`), rather than
 * it being a short link in `<sup>` or `<small>`. [tagOrdinal] counts the start tags before the
 * link (0 = the first tag in the file), so its place can be compared with the note's.
 */
data class NoteRef(val start: Int, val end: Int, val href: String, val explicit: Boolean, val tagOrdinal: Int)

/**
 * A chapter's plain text plus where it is italic, bold, quoted or a scene break, and the
 * note markers in it (EpubParser matches them to their notes).
 */
data class StyledText(val text: String, val styles: List<StyleRange>, val noteRefs: List<NoteRef> = emptyList())

/** Every scene break is shown as this one mark, centered. */
const val SCENE_BREAK_MARK = "⁂"

// A paragraph that is only a few asterisks or ornaments ("* * *", "***", "⁂", "#", "~")
// is a scene break, not text.
private val SCENE_BREAK_RE = Regex("^(?:[*⁂∗✱-✽❖◆◇•·~#§][\\s\\u00A0]*){1,10}$")

fun extractText(html: String): String = extractStyledText(html).text

/**
 * Flattens one chapter's XHTML into plain text: paragraphs separated by a single blank
 * line, no markup, no entities, no image references. Block tags and `<br>` break
 * paragraphs (self-closed or not — real-world EPUB XHTML isn't always consistent about it);
 * script/style content is dropped. `<hr>` and ornament-only paragraphs become a
 * "⁂" scene break. Italic, bold and block quotes are kept as [StyleRange]s.
 *
 * Note markers — a link marked `epub:type="noteref"` / `role="doc-noteref"`, or a short link
 * in `<sup>` or `<small>` that points at an id — are kept as their number alone ("[12]" -> "12")
 * and listed in [StyledText.noteRefs]. Footnotes set beside the text (an `<aside>` marked as a
 * note, or anything marked as a footnote) are left out: they open from their markers.
 */
fun extractStyledText(html: String): StyledText {
    val raw = collectRawText(html)
    val paragraphs = sceneBreaksTidied(splitParagraphs(raw))
    return joinParagraphs(paragraphs, raw.noteRefs)
}

// --- step 1: the text with paragraph markers, and each character's style flags ----------

private const val ITALIC_FLAG = 1
private const val BOLD_FLAG = 2
private const val QUOTE_FLAG = 4

/** A note marker's link, before its place in the final text is known. */
private class RawNoteRef(val href: String, val explicit: Boolean, val tagOrdinal: Int)

/**
 * Text as it comes out of the markup: one style-flags value per character, and the note
 * marker each character belongs to (0 = none, n = [noteRefs] entry n - 1).
 */
private class RawText {
    val text = StringBuilder()
    var flags = IntArray(1024)
    var notes = IntArray(1024)
    val noteRefs = mutableListOf<RawNoteRef>()

    fun append(s: String, flag: Int, note: Int = 0) {
        for (c in s) {
            if (text.length == flags.size) {
                flags = flags.copyOf(flags.size * 2)
                notes = notes.copyOf(notes.size * 2)
            }
            flags[text.length] = flag
            notes[text.length] = note
            text.append(c)
        }
    }
}

/**
 * A link being read. What it shows is held back until it closes: only then is it known
 * whether it's a note marker (`<a href="#n1"><sup>1</sup></a>`) or an ordinary link.
 */
private class OpenLink(val href: String, val explicit: Boolean, val inRaised: Boolean, val tagOrdinal: Int) {
    val pieces = mutableListOf<Pair<String, Int>>()
    var hasRaised = false

    fun isMarker(marker: String): Boolean =
        explicit || ((inRaised || hasRaised) && marker.isNotEmpty() && marker.length <= MAX_MARKER_CHARS)
}

/** The words in a tag's `epub:type` and `role` attributes. */
private fun MarkupToken.StartTag.types(): List<String> =
    "${attrs["epub:type"].orEmpty()} ${attrs["role"].orEmpty()}".split(' ').filter { it.isNotEmpty() }

private fun MarkupToken.StartTag.isHiddenNote(): Boolean {
    val types = types()
    return (name == "aside" && types.any { it in ASIDE_NOTE_TYPES }) || types.any { it in FOOTNOTE_TYPES }
}

/** The link's href if it could be a note marker: it points at an id, and isn't a note's link back. */
private fun MarkupToken.StartTag.noteLinkHref(): String? {
    if (name != "a" || selfClosing) return null
    val href = attrs["href"]?.trim() ?: return null
    if (href.substringAfter('#', "").isEmpty() || ':' in href.substringBefore('#')) return null
    return href.takeIf { types().none { it in BACKLINK_TYPES } }
}

/** "[12]" -> "12", "( * )" -> "*". */
private fun markerText(shown: String): String =
    shown.replace(WHITESPACE_RUN_RE, " ").trim().removeSurrounding("[", "]").removeSurrounding("(", ")").trim()

private val WHITESPACE_RUN_RE = Regex("\\s+")

private fun collectRawText(html: String): RawText {
    val raw = RawText()
    var skipDepth = 0
    var italicDepth = 0
    var boldDepth = 0
    var quoteDepth = 0
    var raisedDepth = 0
    var tagOrdinal = 0
    // A footnote being left out: its tag name, and how deeply that tag nests inside itself.
    var hiddenTag: String? = null
    var hiddenNesting = 0
    var link: OpenLink? = null
    fun flags() = (if (italicDepth > 0) ITALIC_FLAG else 0) or
        (if (boldDepth > 0) BOLD_FLAG else 0) or
        (if (quoteDepth > 0) QUOTE_FLAG else 0)

    fun append(s: String, flag: Int) {
        val open = link
        if (open != null) open.pieces.add(s to flag) else raw.append(s, flag)
    }

    fun closeLink() {
        val open = link ?: return
        link = null
        val marker = markerText(open.pieces.joinToString("") { it.first })
        if (open.isMarker(marker)) {
            raw.noteRefs.add(RawNoteRef(open.href, open.explicit, open.tagOrdinal))
            raw.append(marker.ifEmpty { raw.noteRefs.size.toString() }, 0, note = raw.noteRefs.size)
        } else {
            for ((s, flag) in open.pieces) raw.append(s, flag)
        }
    }

    for (token in tokenizeMarkup(html)) {
        if (hiddenTag != null) {
            if (token is MarkupToken.StartTag) {
                tagOrdinal++
                if (token.name == hiddenTag && !token.selfClosing) hiddenNesting++
            }
            if (token is MarkupToken.EndTag && token.name == hiddenTag && --hiddenNesting == 0) hiddenTag = null
            continue
        }
        when (token) {
            is MarkupToken.StartTag -> {
                val ordinal = tagOrdinal++
                val opens = !token.selfClosing
                if (opens && token.isHiddenNote()) {
                    hiddenTag = token.name
                    hiddenNesting = 1
                    continue
                }
                when {
                    // A self-closed <script/> or <style/> (some readers inject these, e.g.
                    // Kobo's "<script .../>") has no body and no end tag to ever pair with —
                    // don't open a skip region that nothing will close.
                    token.name in SKIPPED_CONTENT_TAGS -> if (opens) skipDepth++
                    token.name == "br" -> append("\n\n", 0)
                    token.name == "hr" -> append("\n\n$SCENE_BREAK_MARK\n\n", 0)
                    token.name in ITALIC_TAGS -> if (opens) italicDepth++
                    token.name in BOLD_TAGS -> if (opens) boldDepth++
                    token.name in RAISED_TAGS -> if (opens) {
                        raisedDepth++
                        link?.hasRaised = true
                    }
                }
                val href = if (skipDepth == 0) token.noteLinkHref() else null
                if (href != null) {
                    closeLink()
                    link = OpenLink(href, token.types().any { it in NOTEREF_TYPES }, raisedDepth > 0, ordinal)
                }
                if (token.name == "blockquote" && opens) quoteDepth++
                if (token.name in BLOCK_BREAK_TAGS) append("\n\n", 0)
            }
            is MarkupToken.EndTag -> {
                when (token.name) {
                    in SKIPPED_CONTENT_TAGS -> skipDepth = maxOf(0, skipDepth - 1)
                    in ITALIC_TAGS -> italicDepth = maxOf(0, italicDepth - 1)
                    in BOLD_TAGS -> boldDepth = maxOf(0, boldDepth - 1)
                    in RAISED_TAGS -> raisedDepth = maxOf(0, raisedDepth - 1)
                    "blockquote" -> quoteDepth = maxOf(0, quoteDepth - 1)
                    "a" -> closeLink()
                }
                if (token.name in BLOCK_BREAK_TAGS) append("\n\n", 0)
            }
            is MarkupToken.Text -> if (skipDepth == 0) append(token.text, flags())
        }
    }
    closeLink()
    return raw
}

// --- step 2: paragraphs, whitespace collapsed ---------------------------------------------

/** [notes]: the note marker each character belongs to, as in [RawText.notes]. */
private class Paragraph(val text: String, val flags: IntArray, val notes: IntArray = IntArray(flags.size)) {
    val isQuote get() = flags.isNotEmpty() && (flags[0] and QUOTE_FLAG) != 0
    val isSceneBreak get() = SCENE_BREAK_RE.matches(text)
}

/** Whitespace that a run of collapses to one space (the regex `\s`). */
private fun isCollapsible(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

/**
 * Two or more line breaks in a row end a paragraph. Inside one, each run of spaces and
 * line breaks becomes a single space, and whitespace at either end is dropped.
 */
private fun splitParagraphs(raw: RawText): List<Paragraph> {
    val paragraphs = mutableListOf<Paragraph>()
    val text = StringBuilder()
    val flags = mutableListOf<Int>()
    val notes = mutableListOf<Int>()
    val gap = StringBuilder() // whitespace since the last visible character
    var newlinesInRow = 0

    fun endParagraph() {
        if (text.isNotEmpty()) paragraphs.add(Paragraph(text.toString(), flags.toIntArray(), notes.toIntArray()))
        text.clear()
        flags.clear()
        notes.clear()
        gap.clear()
    }

    for (i in 0 until raw.text.length) {
        val c = raw.text[i]
        newlinesInRow = if (c == '\n') newlinesInRow + 1 else 0
        when {
            newlinesInRow == 2 -> endParagraph()
            newlinesInRow > 2 -> {}
            isCollapsible(c) -> if (gap.isEmpty() || !gap.endsWith(" ")) gap.append(' ')
            c.isWhitespace() -> gap.append(c) // e.g. a no-break space: kept inside a paragraph
            else -> {
                if (text.isNotEmpty()) {
                    // A space is styled only when the words on both sides are.
                    text.append(gap)
                    val note = if (notes.last() == raw.notes[i]) raw.notes[i] else 0
                    repeat(gap.length) {
                        flags.add(flags.last() and raw.flags[i])
                        notes.add(note)
                    }
                }
                gap.clear()
                text.append(c)
                flags.add(raw.flags[i])
                notes.add(raw.notes[i])
            }
        }
    }
    endParagraph()
    return paragraphs
}

// --- step 3: scene breaks ---------------------------------------------------------------

/** Each scene break becomes "⁂"; repeated ones become one, and none opens or ends the chapter. */
private fun sceneBreaksTidied(paragraphs: List<Paragraph>): List<Paragraph> {
    val result = mutableListOf<Paragraph>()
    for (p in paragraphs) {
        if (!p.isSceneBreak) {
            result.add(p)
        } else if (result.isNotEmpty() && !result.last().isSceneBreak) {
            result.add(Paragraph(SCENE_BREAK_MARK, IntArray(SCENE_BREAK_MARK.length)))
        }
    }
    if (result.lastOrNull()?.isSceneBreak == true) result.removeAt(result.lastIndex)
    return result
}

// --- step 4: one text, with style ranges ------------------------------------------------

private const val PARAGRAPH_SEPARATOR = "\n\n"

private fun joinParagraphs(paragraphs: List<Paragraph>, rawRefs: List<RawNoteRef>): StyledText {
    val text = StringBuilder()
    val styles = mutableListOf<StyleRange>()
    val noteRefs = mutableListOf<NoteRef>()
    var quoteStart = -1
    var quoteEnd = -1

    for (p in paragraphs) {
        if (text.isNotEmpty()) text.append(PARAGRAPH_SEPARATOR)
        val start = text.length
        text.append(p.text)
        val end = text.length

        if (p.isSceneBreak) {
            styles.add(StyleRange(TextStyleKind.SCENE_BREAK, start, end))
        } else {
            styles.addAll(flagRuns(p, ITALIC_FLAG, TextStyleKind.ITALIC, start))
            styles.addAll(flagRuns(p, BOLD_FLAG, TextStyleKind.BOLD, start))
            noteRefs.addAll(noteRuns(p, rawRefs, start))
        }

        // Quoted paragraphs next to each other make one quote.
        if (p.isQuote && !p.isSceneBreak) {
            if (quoteStart < 0) quoteStart = start
            quoteEnd = end
        } else if (quoteStart >= 0) {
            styles.add(StyleRange(TextStyleKind.QUOTE, quoteStart, quoteEnd))
            quoteStart = -1
        }
    }
    if (quoteStart >= 0) styles.add(StyleRange(TextStyleKind.QUOTE, quoteStart, quoteEnd))
    return StyledText(text.toString(), styles.sortedWith(compareBy({ it.start }, { it.style })), noteRefs)
}

/** Each note marker in one paragraph, placed at [offset] in the chapter. */
private fun noteRuns(p: Paragraph, rawRefs: List<RawNoteRef>, offset: Int): List<NoteRef> {
    val refs = mutableListOf<NoteRef>()
    var i = 0
    while (i < p.notes.size) {
        val note = p.notes[i]
        val start = i
        while (i < p.notes.size && p.notes[i] == note) i++
        if (note == 0) continue
        val raw = rawRefs[note - 1]
        refs.add(NoteRef(offset + start, offset + i, raw.href, raw.explicit, raw.tagOrdinal))
    }
    return refs
}

/** The stretches of one paragraph where [flag] is set, trimmed of spaces at either end. */
private fun flagRuns(p: Paragraph, flag: Int, style: TextStyleKind, offset: Int): List<StyleRange> {
    val runs = mutableListOf<StyleRange>()
    var i = 0
    while (i < p.flags.size) {
        if ((p.flags[i] and flag) == 0) {
            i++
            continue
        }
        var start = i
        while (i < p.flags.size && (p.flags[i] and flag) != 0) i++
        var end = i
        while (start < end && p.text[start].isWhitespace()) start++
        while (end > start && p.text[end - 1].isWhitespace()) end--
        if (end > start) runs.add(StyleRange(style, offset + start, offset + end))
    }
    return runs
}
