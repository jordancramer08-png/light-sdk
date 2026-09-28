package com.thelightphone.reader.epub

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/**
 * One readable chapter. [parents] are the titles of the table-of-contents entries above it,
 * outermost first (e.g. "BOOK 1: GARDENS OF THE MOON", "Book One: Pale"); empty at the top.
 */
data class Chapter(
    val title: String,
    val text: String,
    val styles: List<StyleRange> = emptyList(),
    val parents: List<String> = emptyList(),
) {
    val depth: Int get() = parents.size
}
data class Book(
    val title: String,
    val author: String,
    val chapters: List<Chapter>,
    /** The series this book belongs to, if any, and its place in it ("1", "2.5"). */
    val series: String? = null,
    val seriesNumber: String? = null,
    /** The cover image's bytes as stored in the EPUB (JPEG, PNG, …), or null if none was found. */
    val cover: ByteArray? = null,
)

/** Raised when an EPUB can't be converted (e.g. it's DRM-protected). */
sealed class EpubParseException(message: String) : Exception(message)
class DrmProtectedException : EpubParseException("DRM-protected (META-INF/encryption.xml present)")
class InvalidEpubException(message: String) : EpubParseException(message)

/**
 * Bump this whenever a change here (or in HtmlText / ContentFilter) would change a
 * book's chapters or text. The library cache sees the new number and re-parses every book.
 */
const val PARSER_VERSION = 5 // 2: series, and word counts. 3: italic/bold/quotes/scene breaks, nested contents. 4: notes. 5: covers

private const val CONTAINER_PATH = "META-INF/container.xml"
private const val DRM_MARKER_PATH = "META-INF/encryption.xml"
internal val CONTENT_MEDIA_TYPES = setOf("application/xhtml+xml", "text/html")

/**
 * Parses an EPUB into a [Book]: container.xml -> OPF -> metadata (dc:title,
 * dc:creator) -> manifest -> spine (skip `linear="no"`, skip the nav item,
 * only xhtml/html media types) -> chapter titles and their nesting from the NCX
 * first, nav.xhtml fallback -> HTML flattened to text (italic, bold, quotes and
 * scene breaks kept as style ranges; note markers matched to their notes) -> non-content filtering -> untitled
 * chapters numbered "Chapter N" -> the cover image's bytes ([findCover]). Ported from `convert.py` (see CLAUDE.md
 * section 7) — the phone parses EPUBs itself instead of reading pre-converted
 * text from the PC.
 */
object EpubParser {
    fun parse(file: File): Book {
        ZipFile(file).use { zip ->
            if (zip.getEntry(DRM_MARKER_PATH) != null) throw DrmProtectedException()

            val opfPath = findOpfPath(zip)
            val opfDir = zipDirname(opfPath)
            val opfText = readZipText(zip, opfPath)
            val opfRoot = parseXml(opfText)

            val (title, author) = readMetadata(opfRoot)
            val manifest = readManifest(opfRoot, opfDir)
            val (spineItems, tocId) = readSpine(opfRoot)
            val toc = loadToc(zip, manifest, tocId)
            val notes = NoteFinder(zip, spineItems.mapNotNull { (idref, _) -> manifest[idref]?.href })

            val chapters = mutableListOf<Chapter>()
            var lastPlace: TocPlace? = null
            // A divider page ("Prologue") too short to keep, whose text is in the next, unlisted file.
            var dividerTitle: String? = null
            for ((idref, linear) in spineItems) {
                if (!linear) continue
                val item = manifest[idref] ?: continue
                if ("nav" in item.properties) continue
                if (item.mediaType !in CONTENT_MEDIA_TYPES) continue
                val entry = zip.getEntry(item.href) ?: continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val html = decodeHtmlBytes(bytes, item.href)
                val styled = extractStyledText(html)
                val place = toc[item.href]
                // A file the contents doesn't list sits beside (or under) the entry before it.
                val parents = place?.parents ?: lastPlace?.parentsOfNext().orEmpty()
                if (place != null) lastPlace = place
                if (styled.text.isEmpty() || isNonContent(place?.title, styled.text)) {
                    val isDivider = place != null && !place.hasChildren && !isNonContentTitle(place.title)
                    dividerTitle = if (isDivider) place?.title else null
                    continue
                }
                val title = place?.title ?: dividerTitle ?: "Chapter ${chapters.size + 1}"
                dividerTitle = null
                val styles = (styled.styles + noteRanges(notes, item.href, styled)).sortedBy { it.start }
                chapters.add(Chapter(title, styled.text, styles, parents))
            }

            if (chapters.isEmpty()) throw InvalidEpubException("no readable chapters found")
            val series = readSeries(opfText) ?: seriesFromFileName(file.name)
            val cover = findCover(zip, opfRoot, manifest, spineItems.map { it.first })
            return Book(title, author, chapters, series?.name, series?.number, cover)
        }
    }
}

/** A NOTE range for each marker whose note was found; a marker without one stays plain text. */
private fun noteRanges(notes: NoteFinder, file: String, styled: StyledText): List<StyleRange> =
    styled.noteRefs.mapNotNull { ref ->
        val marker = styled.text.substring(ref.start, ref.end)
        notes.noteText(file, marker, ref)?.let { StyleRange(TextStyleKind.NOTE, ref.start, ref.end, note = it) }
    }

/** Same rule as `convert.py`'s `_slugify`, kept identical so saved reading positions carry over. */
fun slugify(title: String): String {
    val slug = Regex("[^a-z0-9]+").replace(title.lowercase(), "-").trim('-')
    return slug.ifEmpty { "untitled" }
}

// --- OPF / NCX / nav parsing -------------------------------------------------

internal data class ManifestItem(val href: String, val mediaType: String, val properties: Set<String>)

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

/**
 * Where a content file sits in the table of contents: its title, the titles of the
 * entries above it (outermost first), and whether other entries are nested under it.
 */
private data class TocPlace(val title: String, val parents: List<String>, val hasChildren: Boolean) {
    /** The parents of a file that follows this one without a contents entry of its own. */
    fun parentsOfNext(): List<String> = if (hasChildren) parents + title else parents
}

/**
 * One entry of the NCX or nav contents. [href] is the resolved content file, or null for a
 * heading with no link of its own.
 */
private class TocEntry(val title: String, val href: String?, val children: List<TocEntry>)

/** Each content file's place in the contents, keyed by resolved href: NCX first, nav.xhtml fallback. */
private fun loadToc(zip: ZipFile, manifest: Map<String, ManifestItem>, tocId: String?): Map<String, TocPlace> {
    val ncxItem = tocId?.let { manifest[it] } ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
    if (ncxItem != null) return placesByHref(readNcxEntries(zip, ncxItem.href))
    val navItem = manifest.values.firstOrNull { "nav" in it.properties }
    if (navItem != null) return placesByHref(readNavDocEntries(zip, navItem.href))
    return emptyMap()
}

/**
 * Walks the contents in reading order. The first entry pointing at a file wins, so a
 * whole-chapter entry's title is kept even when later entries point into the same file at
 * a finer (e.g. scene-level) granularity.
 */
private fun placesByHref(entries: List<TocEntry>): Map<String, TocPlace> {
    val places = LinkedHashMap<String, TocPlace>()
    fun walk(list: List<TocEntry>, parents: List<String>) {
        for (entry in list) {
            val title = repairTitle(entry.title)
            if (entry.href != null) {
                places.putIfAbsent(entry.href, TocPlace(title, parents, hasChildren = entry.children.isNotEmpty()))
            }
            walk(entry.children, parents + title)
        }
    }
    walk(entries, emptyList())
    return places
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

private fun readNcxEntries(zip: ZipFile, ncxPath: String): List<TocEntry> {
    val ncxDir = zipDirname(ncxPath)
    val wrapperRoot = parseXml(readZipText(zip, ncxPath))
    val ncxRoot = firstChild(wrapperRoot, "ncx") ?: return emptyList()
    val navMap = firstChild(ncxRoot, "navmap") ?: return emptyList()

    fun entries(el: XmlNode): List<TocEntry> = directChildren(el, "navpoint").flatMap { navPoint ->
        val title = firstChild(navPoint, "navlabel")
            ?.let { firstChild(it, "text") }
            ?.let { directText(it).trim() }
            .orEmpty()
        val src = firstChild(navPoint, "content")?.attrs?.get("src")?.takeIf { it.isNotEmpty() }
        val children = entries(navPoint)
        // An entry with no title can't be shown; its children move up a level.
        if (title.isEmpty()) children else listOf(TocEntry(title, src?.let { resolvePath(ncxDir, it) }, children))
    }
    return entries(navMap)
}

/** The nav document's `<nav epub:type="toc">`: nested `<ol>`s of `<li>`, each an `<a>` (or a `<span>` heading). */
private fun readNavDocEntries(zip: ZipFile, navPath: String): List<TocEntry> {
    val navDir = zipDirname(navPath)
    val root = parseXml(readZipText(zip, navPath))
    val tocNav = allDescendants(root, "nav").firstOrNull { it.attrs["epub:type"] == "toc" } ?: return emptyList()
    val topList = firstDescendant(tocNav, "ol") ?: firstDescendant(tocNav, "ul") ?: return emptyList()

    fun entries(list: XmlNode): List<TocEntry> = directChildren(list, "li").flatMap { li ->
        val label = navLabelOf(li)
        val title = label?.let { allText(it).replace(Regex("\\s+"), " ").trim() }.orEmpty()
        val href = label?.takeIf { it.name == "a" }?.attrs?.get("href")?.takeIf { it.isNotEmpty() }
        val sublist = firstChild(li, "ol") ?: firstChild(li, "ul")
        val children = sublist?.let { entries(it) }.orEmpty()
        if (title.isEmpty()) children else listOf(TocEntry(title, href?.let { resolvePath(navDir, it) }, children))
    }
    return entries(topList)
}

/** The `<a>` or `<span>` that labels a nav `<li>`, looking past wrappers but not into its sub-list. */
private fun navLabelOf(li: XmlNode): XmlNode? {
    for (child in li.children.filterIsInstance<XmlNode>()) {
        if (child.name == "ol" || child.name == "ul") continue
        if (child.name == "a" || child.name == "span") return child
        (firstDescendant(child, "a") ?: firstDescendant(child, "span"))?.let { return it }
    }
    return null
}

// --- zip / encoding helpers ---------------------------------------------------

private fun readZipBytes(zip: ZipFile, path: String): ByteArray {
    val entry = zip.getEntry(path) ?: throw InvalidEpubException("missing $path")
    return zip.getInputStream(entry).use { it.readBytes() }
}

private fun readZipText(zip: ZipFile, path: String): String = readZipBytes(zip, path).toString(Charsets.UTF_8)

private val DECLARED_ENCODING_RE = Regex("""encoding=["']([\w-]+)["']""")

internal fun decodeHtmlBytes(data: ByteArray, path: String): String {
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

internal fun zipDirname(path: String): String {
    val idx = path.lastIndexOf('/')
    return if (idx >= 0) path.substring(0, idx + 1) else ""
}

internal fun resolvePath(baseDir: String, href: String): String {
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
