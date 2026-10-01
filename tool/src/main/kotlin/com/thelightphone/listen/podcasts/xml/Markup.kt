package com.thelightphone.listen.podcasts.xml

import java.io.Reader
import java.io.StringReader

/**
 * A tag, a closing tag, or a run of text read from XML or HTML. Names keep their namespace
 * prefix ("itunes:image") and are lower-cased; podcast feeds depend on the prefix, unlike
 * the Reader's EPUB parser this is adapted from. [Text.cdata] text is exactly as written;
 * other text has its entities decoded.
 */
sealed class MarkupToken {
    data class StartTag(val name: String, val attrs: Map<String, String>, val selfClosing: Boolean) : MarkupToken()
    data class EndTag(val name: String) : MarkupToken()
    data class Text(val text: String, val cdata: Boolean = false) : MarkupToken()
}

/**
 * Reads markup one token at a time from [input], so a 20 MB feed never sits in memory as a
 * whole. Adapted from the Reader's tolerant tokenizer (it never throws on bad markup), and
 * hardened for hostile input:
 *
 * - DOCTYPEs (with or without an internal subset), comments and processing instructions are
 *   skipped. Entities are only ever decoded from a fixed table, so no external entity is
 *   fetched and no entity expands into more entities ("billion laughs" can't happen).
 * - Numeric entities that aren't real characters (`&#0;`, `&#xD800;`, `&#x110000;`) become
 *   U+FFFD instead of throwing.
 * - A text run keeps at most [maxTextChars] characters and a tag at most [maxTagChars];
 *   anything longer is read past and dropped, so memory stays bounded.
 *
 * [html] treats HTML's empty elements (`<br>`, `<img>`, `<link>`…) as closed even without
 * "/>". Feeds are XML, where `<link>` holds the show's address, so the feed parser turns it off.
 */
class MarkupReader(
    input: Reader,
    private val maxTextChars: Int = DEFAULT_MAX_TEXT_CHARS,
    private val maxTagChars: Int = DEFAULT_MAX_TAG_CHARS,
    private val html: Boolean = true,
) {
    private val reader = if (input is StringReader) input else input.buffered(BUFFER_CHARS)
    private var peeked = NONE

    private fun read(): Int {
        if (peeked != NONE) {
            val c = peeked
            peeked = NONE
            return c
        }
        return reader.read()
    }

    private fun unread(c: Int) {
        peeked = c
    }

    /** The next token, or null at the end of the input. */
    fun next(): MarkupToken? {
        while (true) {
            val c = read()
            if (c < 0) return null
            if (c != '<'.code) {
                unread(c)
                return readText()
            }
            val d = read()
            when {
                d < 0 -> return MarkupToken.Text("<")
                d == '!'.code -> readBang()?.let { return it }
                d == '?'.code -> skipPast("?>")
                d == '/'.code || isNameStart(d) -> {
                    unread(d)
                    readTag()?.let { return it }
                }
                else -> {
                    // A lone "<" in text ("a < b"): keep it as text.
                    unread(d)
                    val rest = readText()
                    return MarkupToken.Text("<" + rest.text)
                }
            }
        }
    }

    private fun readText(): MarkupToken.Text {
        val sb = StringBuilder()
        while (true) {
            val c = read()
            if (c < 0) break
            if (c == '<'.code) {
                unread(c)
                break
            }
            if (sb.length < maxTextChars) sb.append(c.toChar())
        }
        return MarkupToken.Text(decodeEntities(sb.toString()))
    }

    /** After "<!": a comment, CDATA or a declaration such as DOCTYPE. */
    private fun readBang(): MarkupToken? {
        val c = read()
        if (c == '-'.code) {
            val d = read()
            if (d == '-'.code) skipPast("-->") else skipDeclaration()
            return null
        }
        if (c == '['.code) {
            if (matches("CDATA[")) {
                val sb = StringBuilder()
                // "]" characters held back until it's known whether they start the closing "]]>".
                var brackets = 0
                fun add(ch: Char) {
                    if (sb.length < maxTextChars) sb.append(ch)
                }
                while (true) {
                    val x = read()
                    if (x < 0) break
                    if (x == ']'.code) {
                        brackets++
                        continue
                    }
                    if (x == '>'.code && brackets >= 2) {
                        repeat(brackets - 2) { add(']') }
                        brackets = 0
                        break
                    }
                    repeat(brackets) { add(']') }
                    brackets = 0
                    add(x.toChar())
                }
                repeat(brackets) { add(']') }
                return MarkupToken.Text(sb.toString(), cdata = true)
            }
        }
        if (c >= 0) unread(c)
        skipDeclaration()
        return null
    }

    /** Reads [word] if it comes next; otherwise consumes what was read (it's junk anyway). */
    private fun matches(word: String): Boolean {
        for (ch in word) {
            val c = read()
            if (c != ch.code) {
                if (c >= 0) unread(c)
                return false
            }
        }
        return true
    }

    /** Skips a declaration like `<!DOCTYPE x [ <!ENTITY a "b"> ]>`, including its internal subset. */
    private fun skipDeclaration() {
        var depth = 0
        var quote = NONE
        while (true) {
            val c = read()
            if (c < 0) return
            when {
                quote != NONE -> if (c == quote) quote = NONE
                c == '"'.code || c == '\''.code -> quote = c
                c == '['.code -> depth++
                c == ']'.code -> if (depth > 0) depth--
                c == '>'.code && depth == 0 -> return
            }
        }
    }

    /** Skips up to and including [end] ("-->" or "?>"), comparing the last few characters read. */
    private fun skipPast(end: String) {
        val recent = StringBuilder()
        while (true) {
            val c = read()
            if (c < 0) return
            recent.append(c.toChar())
            if (recent.length > end.length) recent.deleteCharAt(0)
            if (recent.length == end.length && recent.toString() == end) return
        }
    }

    /** After "<" (a name or "/" comes next): reads up to the closing ">", minding quotes. */
    private fun readTag(): MarkupToken? {
        val sb = StringBuilder()
        var quote = NONE
        var tooLong = false
        while (true) {
            val c = read()
            if (c < 0) break
            if (quote != NONE) {
                if (c == quote) quote = NONE
            } else if (c == '"'.code || c == '\''.code) {
                quote = c
            } else if (c == '>'.code) {
                break
            }
            if (sb.length < maxTagChars) sb.append(c.toChar()) else tooLong = true
        }
        if (tooLong) return null
        return parseTag(sb.toString(), html)
    }

    companion object {
        const val DEFAULT_MAX_TEXT_CHARS = 1_000_000
        const val DEFAULT_MAX_TAG_CHARS = 64 * 1024
        private const val BUFFER_CHARS = 64 * 1024
        private const val NONE = -2

        private fun isNameStart(c: Int): Boolean =
            (c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || c == '_'.code

        /** Every token in [markup], for short strings such as one description. */
        fun tokens(markup: String): Sequence<MarkupToken> = sequence {
            val r = MarkupReader(StringReader(markup))
            while (true) yield(r.next() ?: break)
        }
    }
}

private val TAG_NAME_RE = Regex("""^[a-zA-Z_][-\w.]*(?::[a-zA-Z_][-\w.]*)?""")
private val ATTR_RE = Regex("""([a-zA-Z_][-\w:.]*)\s*(?:=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+)))?""")

/** Turns the inside of a tag ("a href='x'", "/item", "br/") into a token, or null if it's junk. */
internal fun parseTag(inner: String, html: Boolean = true): MarkupToken? {
    if (inner.startsWith("/")) {
        val name = TAG_NAME_RE.find(inner.substring(1).trimStart())?.value ?: return null
        return MarkupToken.EndTag(name.lowercase())
    }
    val nameMatch = TAG_NAME_RE.find(inner) ?: return null
    val name = nameMatch.value.lowercase()
    var rest = inner.substring(nameMatch.range.last + 1)
    val selfClosing = rest.trimEnd().endsWith("/")
    if (selfClosing) rest = rest.trimEnd().removeSuffix("/")
    val attrs = LinkedHashMap<String, String>()
    for (m in ATTR_RE.findAll(rest)) {
        val value = m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: ""
        attrs.putIfAbsent(m.groupValues[1].lowercase(), decodeEntities(value))
    }
    return MarkupToken.StartTag(name, attrs, selfClosing || (html && name in VOID_TAGS))
}

/** HTML elements that never have content, so they count as closed even without "/>". */
private val VOID_TAGS = setOf(
    "br", "img", "hr", "meta", "link", "input", "area", "base", "col", "embed", "source", "track", "wbr",
)

// The HTML/XML basics, typographic punctuation, and accented Latin letters. An unknown name
// is left as written ("&foo;") rather than dropped. Taken from the Reader, with a few more.
private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…",
    "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
    "sbquo" to "‚", "bdquo" to "„", "prime" to "′", "Prime" to "″",
    "copy" to "©", "reg" to "®", "trade" to "™",
    "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "euml" to "ë",
    "Eacute" to "É", "Egrave" to "È",
    "agrave" to "à", "aacute" to "á", "acirc" to "â", "auml" to "ä", "aring" to "å", "atilde" to "ã",
    "iacute" to "í", "iuml" to "ï", "icirc" to "î", "igrave" to "ì",
    "oacute" to "ó", "ouml" to "ö", "ocirc" to "ô", "ograve" to "ò", "otilde" to "õ", "oslash" to "ø",
    "uacute" to "ú", "uuml" to "ü", "ucirc" to "û", "ugrave" to "ù",
    "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü",
    "ccedil" to "ç", "ntilde" to "ñ", "szlig" to "ß",
    "aelig" to "æ", "oelig" to "œ",
    "deg" to "°", "times" to "×", "divide" to "÷",
    "frac12" to "½", "frac14" to "¼", "frac34" to "¾",
    "laquo" to "«", "raquo" to "»", "middot" to "·", "bull" to "•",
    "dagger" to "†", "Dagger" to "‡", "permil" to "‰",
    "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢",
    "sect" to "§", "para" to "¶", "shy" to "",
    "plusmn" to "±", "sup2" to "²", "sup3" to "³", "micro" to "µ",
    "ordm" to "º", "ordf" to "ª", "iexcl" to "¡", "iquest" to "¿",
    "zwj" to "", "zwnj" to "", "thinsp" to " ", "ensp" to " ", "emsp" to " ",
)

private val ENTITY_RE = Regex("""&(#[xX][0-9a-fA-F]{1,8}|#\d{1,9}|[a-zA-Z][a-zA-Z0-9]{1,15});""")

/** Decodes `&amp;`, `&#8217;`, `&#x2019;` and the named entities above. Never throws. */
fun decodeEntities(text: String): String {
    if ('&' !in text) return text
    return ENTITY_RE.replace(text) { m ->
        val body = m.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") -> codePointText(body.substring(2).toLongOrNull(16))
            body.startsWith("#") -> codePointText(body.substring(1).toLongOrNull())
            else -> NAMED_ENTITIES[body] ?: m.value
        }
    }
}

/** The character for [cp], or U+FFFD when it isn't a real, allowed character. */
private fun codePointText(cp: Long?): String {
    if (cp == null || cp <= 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return "�"
    return String(Character.toChars(cp.toInt()))
}
