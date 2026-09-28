package com.thelightphone.reader.epub

// Tags whose start or end marks a paragraph boundary when flattening HTML to text.
private val BLOCK_BREAK_TAGS = setOf(
    "p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6",
    "blockquote", "section", "article", "header", "footer", "tr",
)
private val SKIPPED_CONTENT_TAGS = setOf("script", "style")
private val WHITESPACE_RE = Regex("\\s+")
private val BLANK_LINE_RE = Regex("\n{2,}")

/**
 * Flattens one chapter's XHTML into plain text: paragraphs separated by a
 * single blank line, no markup, no entities, no image references. Block tags
 * and `<br>` break paragraphs (self-closed or not — real-world EPUB XHTML
 * isn't always consistent about it); script/style content is dropped.
 */
fun extractText(html: String): String {
    val parts = StringBuilder()
    var skipDepth = 0

    for (token in tokenizeMarkup(html)) {
        when (token) {
            is MarkupToken.StartTag -> when {
                // A self-closed <script/> or <style/> (some readers inject these, e.g.
                // Kobo's "<script .../>") has no body and no end tag to ever pair with —
                // don't open a skip region that nothing will close.
                token.name in SKIPPED_CONTENT_TAGS -> if (!token.selfClosing) skipDepth++
                token.name == "br" -> parts.append("\n\n")
                token.name in BLOCK_BREAK_TAGS -> parts.append("\n\n")
            }
            is MarkupToken.EndTag -> when {
                token.name in SKIPPED_CONTENT_TAGS -> skipDepth = maxOf(0, skipDepth - 1)
                token.name in BLOCK_BREAK_TAGS -> parts.append("\n\n")
            }
            is MarkupToken.Text -> if (skipDepth == 0) parts.append(token.text)
        }
    }

    val paragraphs = BLANK_LINE_RE.split(parts.toString())
        .map { it.replace(WHITESPACE_RE, " ").trim() }
        .filter { it.isNotEmpty() }
    return paragraphs.joinToString("\n\n")
}
