package com.thelightphone.reader.epub

import java.util.zip.ZipFile

/** A note longer than this isn't a note: the link pointed at a whole section or chapter. */
const val MAX_NOTE_CHARS = 10_000

// Tags that sit inside a paragraph. A note's id is often on one of these (the number linking
// back to the text, or an empty anchor), so the note is the paragraph around it.
private val INLINE_TAGS = setOf(
    "a", "span", "sup", "sub", "small", "em", "i", "b", "strong", "cite", "abbr", "code", "u", "font",
)
private val OUTERMOST_TAGS = setOf("#root", "html", "body")
private val BACKLINK_TYPES = setOf("backlink", "doc-backlink")

/**
 * Finds the text of the note a marker points to, in any file of the book — including an
 * endnotes file that isn't kept as a chapter. Each file is read and indexed at most once.
 *
 * [spine] is every content file in reading order. A marker found only because its link is
 * in `<sup>` or `<small>` must point forward (a later file, or later in the same file): one
 * pointing back is a note's own link back to the text, not a marker.
 */
internal class NoteFinder(private val zip: ZipFile, spine: List<String>) {
    private val spineIndex = spine.withIndex().associate { (i, href) -> href to i }
    private val documents = HashMap<String, NoteDocument?>()

    /** The note [ref] points to, from a marker in [fromFile], or null if it can't be found. */
    fun noteText(fromFile: String, marker: String, ref: NoteRef): String? {
        val targetFile = if (ref.href.startsWith("#")) fromFile else resolvePath(zipDirname(fromFile), decodePercents(ref.href))
        val id = decodePercents(ref.href.substringAfter('#'))
        val document = document(targetFile) ?: return null
        val element = document.byId[id] ?: return null
        if (!ref.explicit && pointsBack(fromFile, ref, targetFile, document.ordinal(element))) return null
        return document.noteText(element, marker)
    }

    private fun pointsBack(fromFile: String, ref: NoteRef, targetFile: String, targetOrdinal: Int): Boolean {
        if (targetFile == fromFile) return targetOrdinal < ref.tagOrdinal
        val from = spineIndex[fromFile] ?: return false
        val to = spineIndex[targetFile] ?: return false // not in the reading order: an appendix of notes
        return to < from
    }

    private fun document(path: String): NoteDocument? = documents.getOrPut(path) {
        val entry = zip.getEntry(path) ?: return@getOrPut null
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        try {
            NoteDocument(parseXml(decodeHtmlBytes(bytes, path)))
        } catch (e: InvalidEpubException) {
            null
        }
    }
}

/** One content file as an element tree, with every element's id, parent and place in the file. */
private class NoteDocument(root: XmlNode) {
    val byId = HashMap<String, XmlNode>()
    private val parents = HashMap<XmlNode, XmlNode>()
    private val ordinals = HashMap<XmlNode, Int>()

    init {
        // Elements are numbered in the order their start tags appear, as extractStyledText counts them.
        var next = 0
        fun walk(node: XmlNode) {
            for (child in node.children.filterIsInstance<XmlNode>()) {
                parents[child] = node
                ordinals[child] = next++
                child.attrs["id"]?.let { byId.putIfAbsent(it, child) }
                walk(child)
            }
        }
        walk(root)
    }

    fun ordinal(element: XmlNode): Int = ordinals[element] ?: 0

    /**
     * The note's text: the paragraph (or block) holding [element], without the number linking
     * back to the text. If that holds only the number (`<dt>12</dt><dd>…</dd>`), the next block
     * is the note.
     */
    fun noteText(element: XmlNode, marker: String): String? {
        val block = blockAround(element)
        var text = tidyNote(extractText(markupOf(block)), marker)
        if (text.isEmpty()) {
            val next = nextSibling(block) ?: return null
            text = tidyNote(extractText(markupOf(block) + markupOf(next)), marker)
        }
        return text.takeIf { it.isNotEmpty() && it.length <= MAX_NOTE_CHARS }
    }

    private fun blockAround(element: XmlNode): XmlNode {
        var node = element
        while (node.name in INLINE_TAGS) {
            val parent = parents[node] ?: break
            if (parent.name in OUTERMOST_TAGS) break
            node = parent
        }
        return node
    }

    private fun nextSibling(node: XmlNode): XmlNode? {
        val siblings = parents[node]?.children?.filterIsInstance<XmlNode>() ?: return null
        return siblings.getOrNull(siblings.indexOf(node) + 1)
    }
}

/** The note's number linking back to the text ("12", "↩", "[*]"). */
private fun isBacklink(node: XmlNode): Boolean {
    if (node.name != "a" || node.attrs["href"] == null) return false
    val types = "${node.attrs["epub:type"].orEmpty()} ${node.attrs["role"].orEmpty()}".split(' ')
    return types.any { it in BACKLINK_TYPES } || allText(node).trim().length <= MAX_MARKER_CHARS
}

/**
 * Writes an element back out as markup for [extractText], leaving out backlinks. Attributes
 * are dropped, so nothing in a note is taken for a hidden footnote or a note marker.
 */
private fun markupOf(node: XmlNode): String {
    val sb = StringBuilder()
    fun write(n: XmlNode) {
        if (isBacklink(n)) return
        sb.append('<').append(n.name).append('>')
        for (child in n.children) {
            when (child) {
                is String -> sb.append(child.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                is XmlNode -> write(child)
            }
        }
        sb.append("</").append(n.name).append('>')
    }
    write(node)
    return sb.toString()
}

// What's left at the start of a note once its number is gone: "1. Text" -> ". Text".
private val NOTE_LEAD_RE = Regex("^[\\s\\u00A0.:)\\]]+")

/** Drops the note's own number ("12. ", "12 ") and the punctuation after it. */
private fun tidyNote(text: String, marker: String): String {
    var t = NOTE_LEAD_RE.replace(text.trim(), "")
    val afterMarker = t.removePrefix(marker)
    if (marker.isNotEmpty() && afterMarker != t && afterMarker.firstOrNull()?.isLetterOrDigit() != true) {
        t = NOTE_LEAD_RE.replace(afterMarker, "")
    }
    return t.trim()
}

/** "Chapter%201.xhtml" -> "Chapter 1.xhtml". */
private fun decodePercents(s: String): String {
    if ('%' !in s) return s
    val bytes = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < s.length) {
        val hex = if (s[i] == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
        if (hex != null) {
            bytes.write(hex)
            i += 3
        } else {
            bytes.write(s[i].toString().toByteArray(Charsets.UTF_8))
            i++
        }
    }
    return bytes.toString("UTF-8")
}
