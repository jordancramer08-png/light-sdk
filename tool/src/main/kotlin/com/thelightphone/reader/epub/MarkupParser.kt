package com.thelightphone.reader.epub

/**
 * A tag, its closing tag, or a run of text found while scanning XML/XHTML
 * markup. Tag names have any namespace prefix stripped ("dc:title" ->
 * "title"): the rest of this package looks elements up by local name only,
 * the same simplification [tokenizeMarkup] makes.
 */
sealed class MarkupToken {
    data class StartTag(val name: String, val attrs: Map<String, String>, val selfClosing: Boolean) : MarkupToken()
    data class EndTag(val name: String) : MarkupToken()
    data class Text(val text: String) : MarkupToken()
}

private val VOID_TAGS = setOf(
    "br", "img", "hr", "meta", "link", "input", "area", "base", "col", "embed", "source", "track", "wbr",
)

private val TAG_NAME_RE = Regex("""^([a-zA-Z_][-\w.]*:)?([a-zA-Z_][-\w.]*)""")
private val ATTR_RE = Regex("""([a-zA-Z_][-\w:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

/**
 * Scans XML/XHTML markup into a flat token stream. EPUB XHTML in the wild is
 * often not valid XML (unescaped &, unclosed <br>/<img>, stray tags), so this
 * is a tolerant tokenizer, not a validating one — it never throws. Used both
 * for the structural documents (container.xml, OPF, NCX, nav.xhtml) and for
 * chapter content (see [extractText]).
 */
fun tokenizeMarkup(markup: String): List<MarkupToken> {
    val tokens = mutableListOf<MarkupToken>()
    val n = markup.length
    var i = 0
    var textStart = 0

    fun flushText(end: Int) {
        if (end > textStart) {
            val decoded = decodeEntities(markup.substring(textStart, end))
            if (decoded.isNotEmpty()) tokens.add(MarkupToken.Text(decoded))
        }
    }

    while (i < n) {
        if (markup[i] != '<') {
            i++
            continue
        }
        flushText(i)

        if (markup.startsWith("<!--", i)) {
            val end = markup.indexOf("-->", i + 4)
            i = if (end < 0) n else end + 3
            textStart = i
            continue
        }
        if (markup.startsWith("<![CDATA[", i)) {
            val end = markup.indexOf("]]>", i + 9)
            val close = if (end < 0) n else end
            if (close > i + 9) tokens.add(MarkupToken.Text(markup.substring(i + 9, close)))
            i = if (end < 0) n else end + 3
            textStart = i
            continue
        }
        if (markup.startsWith("<?", i)) {
            val end = markup.indexOf("?>", i + 2)
            i = if (end < 0) n else end + 2
            textStart = i
            continue
        }
        if (markup.startsWith("<!", i)) {
            val end = markup.indexOf(">", i + 2)
            i = if (end < 0) n else end + 1
            textStart = i
            continue
        }

        val tagEnd = markup.indexOf('>', i + 1)
        if (tagEnd < 0) {
            i = n
            textStart = n
            break
        }
        val inner = markup.substring(i + 1, tagEnd)
        i = tagEnd + 1
        textStart = i

        if (inner.startsWith("/")) {
            val nameMatch = TAG_NAME_RE.find(inner.substring(1))
            val name = nameMatch?.groupValues?.get(2)?.lowercase() ?: continue
            tokens.add(MarkupToken.EndTag(name))
            continue
        }

        val nameMatch = TAG_NAME_RE.find(inner) ?: continue
        val name = nameMatch.groupValues[2].lowercase()
        val rest = inner.substring(nameMatch.range.last + 1)
        val explicitSelfClose = rest.trimEnd().endsWith("/")
        val attrsSource = if (explicitSelfClose) rest.trimEnd().removeSuffix("/") else rest
        val attrs = LinkedHashMap<String, String>()
        for (m in ATTR_RE.findAll(attrsSource)) {
            val value = if (m.groups[2] != null) m.groupValues[2] else m.groupValues[3]
            attrs[m.groupValues[1]] = decodeEntities(value)
        }
        tokens.add(MarkupToken.StartTag(name, attrs, explicitSelfClose || name in VOID_TAGS))
    }
    flushText(n)
    return tokens
}

// Named entities EPUB content actually uses: the HTML/XML basics, curly quotes
// and dashes publishers favor, and the accented Latin-1 letters common in
// author names and non-English text. An unrecognized name (rare) is left as
// literal "&name;" rather than dropped.
private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…",
    "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
    "copy" to "©", "reg" to "®", "trade" to "™",
    "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "euml" to "ë",
    "agrave" to "à", "acirc" to "â", "auml" to "ä", "aring" to "å",
    "iuml" to "ï", "icirc" to "î", "igrave" to "ì",
    "ouml" to "ö", "ocirc" to "ô", "ograve" to "ò",
    "uuml" to "ü", "ucirc" to "û", "ugrave" to "ù",
    "ccedil" to "ç", "ntilde" to "ñ", "szlig" to "ß",
    "aelig" to "æ", "oelig" to "œ",
    "deg" to "°", "times" to "×", "divide" to "÷",
    "frac12" to "½", "frac14" to "¼", "frac34" to "¾",
    "laquo" to "«", "raquo" to "»", "middot" to "·", "bull" to "•",
    "dagger" to "†", "Dagger" to "‡", "permil" to "‰",
    "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢",
    "sect" to "§", "para" to "¶", "shy" to "­",
    "plusmn" to "±", "sup2" to "²", "sup3" to "³", "micro" to "µ",
    "ordm" to "º", "ordf" to "ª", "iexcl" to "¡", "iquest" to "¿",
)

private val ENTITY_RE = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")

fun decodeEntities(text: String): String {
    if ('&' !in text) return text
    return ENTITY_RE.replace(text) { m ->
        val body = m.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") ->
                body.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            body.startsWith("#") ->
                body.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> NAMED_ENTITIES[body] ?: m.value
        }
    }
}

/** A minimal parsed-XML node: children are either nested [XmlNode]s or text runs. */
class XmlNode(val name: String, val attrs: Map<String, String>) {
    val children = mutableListOf<Any>()
}

/**
 * Builds a tolerant element tree from XML/XHTML markup, for the structural
 * EPUB documents (container.xml, OPF, NCX, nav.xhtml). Returns a synthetic
 * "#root" node wrapping the document's top-level element(s) — mirrors what
 * `xml.etree.ElementTree.fromstring` gives convert.py, minus the one extra
 * wrapper hop. An unmatched end tag is ignored rather than raising.
 */
fun parseXml(markup: String): XmlNode {
    val root = XmlNode("#root", emptyMap())
    val stack = mutableListOf(root)
    for (token in tokenizeMarkup(markup)) {
        when (token) {
            is MarkupToken.StartTag -> {
                val node = XmlNode(token.name, token.attrs)
                stack.last().children.add(node)
                if (!token.selfClosing) stack.add(node)
            }
            is MarkupToken.EndTag -> {
                val idx = stack.indexOfLast { it.name == token.name }
                if (idx > 0) {
                    while (stack.size > idx) stack.removeAt(stack.size - 1)
                }
            }
            is MarkupToken.Text -> stack.last().children.add(token.text)
        }
    }
    return root
}

/** First direct child element named [name] (ElementTree's `el.find(tag)`). */
fun firstChild(node: XmlNode, name: String): XmlNode? =
    node.children.filterIsInstance<XmlNode>().firstOrNull { it.name == name }

/** All direct child elements named [name] (ElementTree's `el.findall(tag)`). */
fun directChildren(node: XmlNode, name: String): List<XmlNode> =
    node.children.filterIsInstance<XmlNode>().filter { it.name == name }

/** First descendant element named [name], depth-first (ElementTree's `el.find(".//tag")`). */
fun firstDescendant(node: XmlNode, name: String): XmlNode? {
    for (child in node.children.filterIsInstance<XmlNode>()) {
        if (child.name == name) return child
        firstDescendant(child, name)?.let { return it }
    }
    return null
}

/** All descendant elements named [name], depth-first (ElementTree's `el.findall(".//tag")`). */
fun allDescendants(node: XmlNode, name: String): List<XmlNode> {
    val result = mutableListOf<XmlNode>()
    fun walk(n: XmlNode) {
        for (child in n.children.filterIsInstance<XmlNode>()) {
            if (child.name == name) result.add(child)
            walk(child)
        }
    }
    walk(node)
    return result
}

/** This element's own text, ignoring any nested elements (ElementTree's `.text`). */
fun directText(node: XmlNode): String = node.children.filterIsInstance<String>().joinToString("")

/** All text under this element, nested or not (ElementTree's `.itertext()`). */
fun allText(node: XmlNode): String {
    val sb = StringBuilder()
    fun walk(n: XmlNode) {
        for (child in n.children) {
            when (child) {
                is String -> sb.append(child)
                is XmlNode -> walk(child)
            }
        }
    }
    walk(node)
    return sb.toString()
}
