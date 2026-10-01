package com.thelightphone.listen.podcasts.xml

/**
 * Turns feed descriptions (which are often HTML) into clean text to read on the phone.
 * HTML is never shown as live HTML.
 *
 * - Paragraphs and headings are separated by a blank line; `<br>` starts a new line.
 * - List items become "• item" (or "1. item" in a numbered list).
 * - Links keep their text, followed by the address in brackets when the text doesn't
 *   already say it ("our site (https://example.com)").
 * - Images, scripts, styles, embedded players and forms are dropped. Entities are decoded.
 * - Text without any tags keeps its own line breaks.
 *
 * Returns "" when nothing readable is left.
 */
object HtmlToText {

    fun convert(html: String?): String {
        if (html.isNullOrBlank()) return ""
        return if (TAG_RE.containsMatchIn(html)) fromHtml(html) else fromPlain(decodeEntities(html))
    }

    /** One line, for titles and names: tags dropped, entities decoded, spaces collapsed. */
    fun inline(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val plain = if ('<' in text) convert(text) else decodeEntities(text)
        // Feeds often double-escape ("&amp;#8217;"), which leaves an entity after one decode.
        return decodeEntities(plain).replace(SPACES_RE, " ").trim()
    }

    private fun fromPlain(text: String): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { it.replace(SPACES_RE, " ").trim() }
        return tidy(lines.joinToString("\n"))
    }

    private fun fromHtml(html: String): String {
        val out = StringBuilder()
        var skipDepth = 0
        var preDepth = 0
        val lists = ArrayDeque<IntArray>() // per open list: [isOrdered (1/0), next number]
        var link: OpenLink? = null

        fun target(): StringBuilder = link?.text ?: out

        fun breakLine(blank: Boolean) {
            val sb = target()
            trimTrailingSpaces(sb)
            if (sb.isEmpty()) return
            val want = if (blank) "\n\n" else "\n"
            if (sb.endsWith(want)) return
            if (blank && sb.endsWith("\n")) sb.append('\n') else sb.append(want)
        }

        fun appendText(text: String) {
            val sb = target()
            if (preDepth > 0) {
                sb.append(text)
                return
            }
            val collapsed = text.replace(SPACES_RE, " ")
            if (collapsed.isEmpty()) return
            if (collapsed == " ") {
                if (sb.isNotEmpty() && !sb.endsWith(" ") && !sb.endsWith("\n")) sb.append(' ')
                return
            }
            val atLineStart = sb.isEmpty() || sb.endsWith("\n")
            sb.append(if (atLineStart) collapsed.trimStart() else collapsed)
        }

        for (token in MarkupReader.tokens(html)) {
            when (token) {
                is MarkupToken.StartTag -> {
                    val name = token.name
                    if (name in SKIPPED_WITH_CONTENT) {
                        if (!token.selfClosing) skipDepth++
                        continue
                    }
                    if (skipDepth > 0) continue
                    when {
                        name == "br" -> {
                            trimTrailingSpaces(target())
                            target().append('\n')
                        }
                        name == "li" -> {
                            breakLine(blank = false)
                            val list = lists.lastOrNull()
                            if (list != null && list[0] == 1) {
                                target().append("${list[1]}. ")
                                list[1]++
                            } else {
                                target().append("• ")
                            }
                        }
                        name == "ul" || name == "ol" -> {
                            breakLine(blank = lists.isEmpty())
                            if (!token.selfClosing) {
                                val start = token.attrs["start"]?.toIntOrNull() ?: 1
                                lists.addLast(intArrayOf(if (name == "ol") 1 else 0, start))
                            }
                        }
                        name == "pre" -> {
                            breakLine(blank = true)
                            if (!token.selfClosing) preDepth++
                        }
                        name in BLOCK_TAGS -> breakLine(blank = true)
                        name == "td" || name == "th" -> appendText(" ")
                        name == "a" && !token.selfClosing && link == null ->
                            link = OpenLink(token.attrs["href"]?.trim().orEmpty())
                    }
                }
                is MarkupToken.EndTag -> {
                    val name = token.name
                    if (name in SKIPPED_WITH_CONTENT) {
                        if (skipDepth > 0) skipDepth--
                        continue
                    }
                    if (skipDepth > 0) continue
                    when {
                        name == "ul" || name == "ol" -> {
                            lists.removeLastOrNull()
                            breakLine(blank = lists.isEmpty())
                        }
                        name == "li" -> breakLine(blank = false)
                        name == "pre" -> {
                            if (preDepth > 0) preDepth--
                            breakLine(blank = true)
                        }
                        name in BLOCK_TAGS -> breakLine(blank = true)
                        name == "a" -> link?.let {
                            link = null
                            appendText(it.render())
                        }
                    }
                }
                is MarkupToken.Text -> if (skipDepth == 0) {
                    // CDATA inside a description is text, not more markup.
                    appendText(token.text)
                }
            }
        }
        link?.let { appendText(it.render()) }
        return tidy(out.toString())
    }

    /** A link being read: its address and the text it shows. */
    private class OpenLink(val href: String) {
        val text = StringBuilder()

        fun render(): String {
            val shown = text.toString().replace(SPACES_RE, " ").trim()
            val address = href.takeIf { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("mailto:") }
                ?: return shown
            val bare = bareAddress(address)
            if (shown.isEmpty()) return address.removePrefix("mailto:")
            if (bare.isEmpty() || bareAddress(shown).contains(bare, ignoreCase = true)) return shown
            return "$shown (${address.removePrefix("mailto:")})"
        }
    }

    /** "https://www.example.com/a/" → "example.com/a", for "does the text already say it?". */
    private fun bareAddress(s: String): String =
        s.trim().removePrefix("mailto:").replace(SCHEME_RE, "").removePrefix("www.").trimEnd('/')

    private fun trimTrailingSpaces(sb: StringBuilder) {
        while (sb.isNotEmpty() && (sb.last() == ' ' || sb.last() == '\t')) sb.setLength(sb.length - 1)
    }

    /** Trims each line, keeps at most one blank line in a row, and trims the whole. */
    private fun tidy(text: String): String {
        val lines = text.split('\n').map { it.replace(' ', ' ').trimEnd() }
        val out = StringBuilder()
        var blank = 0
        for (line in lines) {
            val trimmed = line.trimStart()
            if (trimmed.isEmpty()) {
                blank++
                continue
            }
            if (out.isNotEmpty()) out.append(if (blank > 0) "\n\n" else "\n")
            out.append(trimmed)
            blank = 0
        }
        return out.toString()
    }

    private val TAG_RE = Regex("""<(?:[a-zA-Z][-\w:]*|/[a-zA-Z][-\w:]*|!--)[^>]*>""")
    private val SPACES_RE = Regex("""[ \t\r\n ]+""")
    private val SCHEME_RE = Regex("""^[a-zA-Z]+://""")

    private val BLOCK_TAGS = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "section", "article",
        "header", "footer", "table", "tr", "hr", "figure", "figcaption", "dl", "dt", "dd", "main", "aside",
        "address", "center",
    )

    /** Elements dropped together with everything inside them. */
    private val SKIPPED_WITH_CONTENT = setOf(
        "script", "style", "head", "title", "noscript", "iframe", "object", "svg", "video", "audio",
        "canvas", "form", "button", "select", "textarea", "template", "picture", "map",
    )
}
