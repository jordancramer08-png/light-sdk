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

/** What a stretch of chapter text looks like, besides plain. */
@Serializable
enum class TextStyleKind {
    ITALIC,
    BOLD,

    /** One or more paragraphs from a `<blockquote>`, drawn indented. */
    QUOTE,

    /** A scene break: its own paragraph, always "⁂", drawn centered. */
    SCENE_BREAK,
}

/** [style] covers the characters from [start] up to (not including) [end]. */
@Serializable
data class StyleRange(val style: TextStyleKind, val start: Int, val end: Int)

/** A chapter's plain text plus where it is italic, bold, quoted or a scene break. */
data class StyledText(val text: String, val styles: List<StyleRange>)

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
 */
fun extractStyledText(html: String): StyledText {
    val raw = collectRawText(html)
    val paragraphs = sceneBreaksTidied(splitParagraphs(raw))
    return joinParagraphs(paragraphs)
}

// --- step 1: the text with paragraph markers, and each character's style flags ----------

private const val ITALIC_FLAG = 1
private const val BOLD_FLAG = 2
private const val QUOTE_FLAG = 4

/** Text as it comes out of the markup, one style-flags value per character. */
private class RawText {
    val text = StringBuilder()
    var flags = IntArray(1024)

    fun append(s: String, flag: Int) {
        for (c in s) {
            if (text.length == flags.size) flags = flags.copyOf(flags.size * 2)
            flags[text.length] = flag
            text.append(c)
        }
    }
}

private fun collectRawText(html: String): RawText {
    val raw = RawText()
    var skipDepth = 0
    var italicDepth = 0
    var boldDepth = 0
    var quoteDepth = 0
    fun flags() = (if (italicDepth > 0) ITALIC_FLAG else 0) or
        (if (boldDepth > 0) BOLD_FLAG else 0) or
        (if (quoteDepth > 0) QUOTE_FLAG else 0)

    for (token in tokenizeMarkup(html)) {
        when (token) {
            is MarkupToken.StartTag -> {
                val opens = !token.selfClosing
                when {
                    // A self-closed <script/> or <style/> (some readers inject these, e.g.
                    // Kobo's "<script .../>") has no body and no end tag to ever pair with —
                    // don't open a skip region that nothing will close.
                    token.name in SKIPPED_CONTENT_TAGS -> if (opens) skipDepth++
                    token.name == "br" -> raw.append("\n\n", 0)
                    token.name == "hr" -> raw.append("\n\n$SCENE_BREAK_MARK\n\n", 0)
                    token.name in ITALIC_TAGS -> if (opens) italicDepth++
                    token.name in BOLD_TAGS -> if (opens) boldDepth++
                }
                if (token.name == "blockquote" && opens) quoteDepth++
                if (token.name in BLOCK_BREAK_TAGS) raw.append("\n\n", 0)
            }
            is MarkupToken.EndTag -> {
                when (token.name) {
                    in SKIPPED_CONTENT_TAGS -> skipDepth = maxOf(0, skipDepth - 1)
                    in ITALIC_TAGS -> italicDepth = maxOf(0, italicDepth - 1)
                    in BOLD_TAGS -> boldDepth = maxOf(0, boldDepth - 1)
                    "blockquote" -> quoteDepth = maxOf(0, quoteDepth - 1)
                }
                if (token.name in BLOCK_BREAK_TAGS) raw.append("\n\n", 0)
            }
            is MarkupToken.Text -> if (skipDepth == 0) raw.append(token.text, flags())
        }
    }
    return raw
}

// --- step 2: paragraphs, whitespace collapsed ---------------------------------------------

private class Paragraph(val text: String, val flags: IntArray) {
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
    val gap = StringBuilder() // whitespace since the last visible character
    var newlinesInRow = 0

    fun endParagraph() {
        if (text.isNotEmpty()) paragraphs.add(Paragraph(text.toString(), flags.toIntArray()))
        text.clear()
        flags.clear()
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
                    repeat(gap.length) { flags.add(flags.last() and raw.flags[i]) }
                }
                gap.clear()
                text.append(c)
                flags.add(raw.flags[i])
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

private fun joinParagraphs(paragraphs: List<Paragraph>): StyledText {
    val text = StringBuilder()
    val styles = mutableListOf<StyleRange>()
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
    return StyledText(text.toString(), styles.sortedWith(compareBy({ it.start }, { it.style })))
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
