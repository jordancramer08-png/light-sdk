package com.thelightphone.reader.epub

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

data class Chapter(val title: String, val text: String)
data class Book(val title: String, val author: String, val chapters: List<Chapter>)

/** Raised when an EPUB can't be converted (e.g. it's DRM-protected). */
sealed class EpubParseException(message: String) : Exception(message)
class DrmProtectedException : EpubParseException("DRM-protected (META-INF/encryption.xml present)")
class InvalidEpubException(message: String) : EpubParseException(message)

private const val CONTAINER_PATH = "META-INF/container.xml"
private const val DRM_MARKER_PATH = "META-INF/encryption.xml"
private val CONTENT_MEDIA_TYPES = setOf("application/xhtml+xml", "text/html")

/**
 * Parses an EPUB into a [Book]: container.xml -> OPF -> metadata (dc:title,
 * dc:creator) -> manifest -> spine (skip `linear="no"`, skip the nav item,
 * only xhtml/html media types) -> chapter titles from the NCX first, nav.xhtml
 * fallback -> HTML flattened to text -> non-content filtering -> untitled
 * chapters numbered "Chapter N". Ported from `convert.py` (see CLAUDE.md
 * section 7) — the phone parses EPUBs itself instead of reading pre-converted
 * text from the PC.
 */
object EpubParser {
    fun parse(file: File): Book {
        ZipFile(file).use { zip ->
            if (zip.getEntry(DRM_MARKER_PATH) != null) throw DrmProtectedException()

            val opfPath = findOpfPath(zip)
            val opfDir = zipDirname(opfPath)
            val opfRoot = parseXml(readZipText(zip, opfPath))

            val (title, author) = readMetadata(opfRoot)
            val manifest = readManifest(opfRoot, opfDir)
            val (spineItems, tocId) = readSpine(opfRoot)
            val titles = loadTitles(zip, manifest, tocId)

            val chapters = mutableListOf<Chapter>()
            for ((idref, linear) in spineItems) {
                if (!linear) continue
                val item = manifest[idref] ?: continue
                if ("nav" in item.properties) continue
                if (item.mediaType !in CONTENT_MEDIA_TYPES) continue
                val entry = zip.getEntry(item.href) ?: continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val html = decodeHtmlBytes(bytes, item.href)
                val text = extractText(html)
                if (text.isEmpty()) continue
                val rawTitle = titles[item.href]
                if (isNonContent(rawTitle, text)) continue
                chapters.add(Chapter(title = rawTitle ?: "Chapter ${chapters.size + 1}", text = text))
            }

            if (chapters.isEmpty()) throw InvalidEpubException("no readable chapters found")
            return Book(title = title, author = author, chapters = chapters)
        }
    }
}

/** Same rule as `convert.py`'s `_slugify`, kept identical so saved reading positions carry over. */
fun slugify(title: String): String {
    val slug = Regex("[^a-z0-9]+").replace(title.lowercase(), "-").trim('-')
    return slug.ifEmpty { "untitled" }
}

// --- OPF / NCX / nav parsing -------------------------------------------------

private data class ManifestItem(val href: String, val mediaType: String, val properties: Set<String>)

private fun findOpfPath(zip: ZipFile): String {
    val root = parseXml(readZipText(zip, CONTAINER_PATH))
    val rootfile = firstDescendant(root, "rootfile")
    return rootfile?.attrs?.get("full-path")
        ?: throw InvalidEpubException("$CONTAINER_PATH has no <rootfile full-path=...>")
}

private fun readMetadata(opfRoot: XmlNode): Pair<String, String> {
    val title = firstDescendant(opfRoot, "title")?.let { directText(it).trim() }.orEmpty()
    val author = firstDescendant(opfRoot, "creator")?.let { directText(it).trim() }.orEmpty()
    return (title.ifEmpty { "Untitled" }) to (author.ifEmpty { "Unknown" })
}

private fun readManifest(opfRoot: XmlNode, opfDir: String): Map<String, ManifestItem> {
    val manifestEl = firstDescendant(opfRoot, "manifest") ?: return emptyMap()
    val items = LinkedHashMap<String, ManifestItem>()
    for (item in directChildren(manifestEl, "item")) {
        val id = item.attrs["id"] ?: continue
        val href = item.attrs["href"] ?: continue
        items[id] = ManifestItem(
            href = resolvePath(opfDir, href),
            mediaType = item.attrs["media-type"] ?: "",
            properties = item.attrs["properties"]?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.toSet()
                ?: emptySet(),
        )
    }
    return items
}

private fun readSpine(opfRoot: XmlNode): Pair<List<Pair<String, Boolean>>, String?> {
    val spineEl = firstDescendant(opfRoot, "spine") ?: throw InvalidEpubException("OPF has no <spine>")
    val items = directChildren(spineEl, "itemref").mapNotNull { itemref ->
        val idref = itemref.attrs["idref"] ?: return@mapNotNull null
        idref to ((itemref.attrs["linear"] ?: "yes") != "no")
    }
    return items to spineEl.attrs["toc"]
}

/** Chapter titles keyed by resolved content href, per CLAUDE.md: NCX first, nav.xhtml fallback. */
private fun loadTitles(zip: ZipFile, manifest: Map<String, ManifestItem>, tocId: String?): Map<String, String> {
    val ncxItem = tocId?.let { manifest[it] } ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
    if (ncxItem != null) {
        return readNcxTitles(zip, ncxItem.href).mapValues { repairTitle(it.value) }
    }
    val navItem = manifest.values.firstOrNull { "nav" in it.properties }
    if (navItem != null) {
        return readNavDocTitles(zip, navItem.href).mapValues { repairTitle(it.value) }
    }
    return emptyMap()
}

// Some publishers' NCX/nav titles are generated by flattening styled text runs
// (e.g. a word plus an adjacent italic word) and drop the space at the seam,
// producing things like "theOlympic" or "PARTIII:". Only the title string is
// affected — the chapter body keeps its own, unmangled text nodes — so this
// repair is applied to titles only, never to extracted chapter text.
private val ROMAN_NUMERALS = listOf( // longest first, so a greedy search finds the full suffix
    "XX", "XIX", "XVIII", "XVII", "XVI", "XV", "XIV", "XIII", "XII", "XI",
    "X", "IX", "VIII", "VII", "VI", "V", "IV", "III", "II", "I",
)
private val ROMAN_LETTERS = setOf('I', 'V', 'X', 'L', 'C', 'D', 'M')
private val CAPS_WORD_RE = Regex("[A-Z]{2,}")
private val LOWER_UPPER_SEAM_RE = Regex("(?<=[a-z])(?=[A-Z])")

fun repairTitle(title: String): String {
    val spaced = LOWER_UPPER_SEAM_RE.replace(title, " ")
    return CAPS_WORD_RE.replace(spaced) { splitWordAndNumeral(it.value) }
}

private fun splitWordAndNumeral(word: String): String {
    for (numeral in ROMAN_NUMERALS) {
        if (word.endsWith(numeral)) {
            val prefix = word.substring(0, word.length - numeral.length)
            // Require a 2+ letter prefix with no roman-numeral letters in it, so a
            // standalone numeral ("XVI") or a short word ("SIX", prefix "S") isn't
            // torn in two.
            if (prefix.length >= 2 && prefix.none { it in ROMAN_LETTERS }) {
                return "$prefix $numeral"
            }
        }
    }
    return word
}

private fun readNcxTitles(zip: ZipFile, ncxPath: String): Map<String, String> {
    val ncxDir = zipDirname(ncxPath)
    val wrapperRoot = parseXml(readZipText(zip, ncxPath))
    val ncxRoot = firstChild(wrapperRoot, "ncx") ?: return emptyMap()
    val navMap = firstChild(ncxRoot, "navmap") ?: return emptyMap()
    val titles = LinkedHashMap<String, String>()

    fun walk(el: XmlNode) {
        for (navPoint in directChildren(el, "navpoint")) {
            val labelText = firstChild(navPoint, "navlabel")
                ?.let { firstChild(it, "text") }
                ?.let { directText(it).trim() }
            val src = firstChild(navPoint, "content")?.attrs?.get("src")
            if (!labelText.isNullOrEmpty() && !src.isNullOrEmpty()) {
                // First occurrence wins, so a whole-chapter navPoint's title is kept
                // even when later navPoints point into the same file at a finer
                // (e.g. scene-level) granularity.
                titles.putIfAbsent(resolvePath(ncxDir, src), labelText)
            }
            walk(navPoint)
        }
    }
    walk(navMap)
    return titles
}

private fun readNavDocTitles(zip: ZipFile, navPath: String): Map<String, String> {
    val navDir = zipDirname(navPath)
    val root = parseXml(readZipText(zip, navPath))
    val tocNav = allDescendants(root, "nav").firstOrNull { it.attrs["epub:type"] == "toc" } ?: return emptyMap()
    val titles = LinkedHashMap<String, String>()
    for (a in allDescendants(tocNav, "a")) {
        val href = a.attrs["href"] ?: continue
        val text = allText(a).replace(Regex("\\s+"), " ").trim()
        if (text.isNotEmpty()) titles.putIfAbsent(resolvePath(navDir, href), text)
    }
    return titles
}

// --- zip / encoding helpers ---------------------------------------------------

private fun readZipBytes(zip: ZipFile, path: String): ByteArray {
    val entry = zip.getEntry(path) ?: throw InvalidEpubException("missing $path")
    return zip.getInputStream(entry).use { it.readBytes() }
}

private fun readZipText(zip: ZipFile, path: String): String = readZipBytes(zip, path).toString(Charsets.UTF_8)

private val DECLARED_ENCODING_RE = Regex("""encoding=["']([\w-]+)["']""")

private fun decodeHtmlBytes(data: ByteArray, path: String): String {
    // XML/XHTML declarations are always ASCII at the very start of the file,
    // regardless of the document's actual encoding, so it's safe to scan for
    // one before we know what that encoding is.
    val head = String(data, 0, minOf(data.size, 500), Charsets.ISO_8859_1)
    val declared = DECLARED_ENCODING_RE.find(head)?.groupValues?.get(1)
    for (encodingName in listOfNotNull(declared, "UTF-8", "windows-1252")) {
        try {
            return decodeStrict(data, encodingName)
        } catch (e: Exception) {
            continue
        }
    }
    throw InvalidEpubException("could not decode $path in any known encoding")
}

private fun decodeStrict(data: ByteArray, encodingName: String): String {
    val decoder = Charset.forName(encodingName).newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return decoder.decode(ByteBuffer.wrap(data)).toString()
}

private fun zipDirname(path: String): String {
    val idx = path.lastIndexOf('/')
    return if (idx >= 0) path.substring(0, idx + 1) else ""
}

private fun resolvePath(baseDir: String, href: String): String {
    val withoutFragment = href.substringBefore('#')
    val joined = if (withoutFragment.startsWith("/")) withoutFragment else baseDir + withoutFragment
    val stack = mutableListOf<String>()
    for (part in joined.split("/")) {
        when (part) {
            "", "." -> {}
            ".." -> if (stack.isNotEmpty() && stack.last() != "..") stack.removeAt(stack.size - 1) else stack.add("..")
            else -> stack.add(part)
        }
    }
    return stack.joinToString("/")
}
