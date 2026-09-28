package com.thelightphone.reader.epub

import java.util.zip.ZipFile

/**
 * Finding a book's cover image (CLAUDE.md 6). Only the image's bytes are returned, as
 * stored in the EPUB: decoding and shrinking them is the data layer's job, since that needs
 * Android's image classes and this package must run in plain JVM tests.
 */

/** Anything bigger than this is not a cover we want to decode on a phone. */
internal const val MAX_COVER_BYTES = 20 * 1024 * 1024

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

/**
 * The cover's bytes, trying in order: the OPF `<meta name="cover" content="…">` item, an
 * EPUB 3 item with `properties="cover-image"`, an image item whose id or file name holds
 * "cover", and last the first image in the first spine file (the cover page). A candidate
 * whose file is missing or empty is skipped. Null when nothing is found.
 */
internal fun findCover(
    zip: ZipFile,
    opfRoot: XmlNode,
    manifest: Map<String, ManifestItem>,
    spineIds: List<String>,
): ByteArray? {
    val candidates = coverCandidates(opfRoot, manifest) + sequence {
        firstSpineFile(manifest, spineIds)?.let { page -> firstImageIn(zip, page)?.let { yield(it) } }
    }
    return candidates.firstNotNullOfOrNull { readImage(zip, it) }
}

/** The hrefs the OPF itself marks (or names) as the cover, best first. */
private fun coverCandidates(opfRoot: XmlNode, manifest: Map<String, ManifestItem>): Sequence<String> = sequence {
    val images = manifest.filterValues { it.isImage() }
    metaCoverId(opfRoot)?.let { id ->
        // Usually an item id; a few books put the file's path there instead.
        (images[id] ?: images.values.firstOrNull { it.href.endsWith(id) })?.let { yield(it.href) }
    }
    images.values.filter { "cover-image" in it.properties }.forEach { yield(it.href) }
    images.filter { (id, item) -> id.contains("cover", ignoreCase = true) || fileName(item.href).contains("cover", ignoreCase = true) }
        .values.forEach { yield(it.href) }
}

/** `<meta name="cover" content="cover-id"/>`, the EPUB 2 way of naming the cover. */
private fun metaCoverId(opfRoot: XmlNode): String? =
    allDescendants(opfRoot, "meta")
        .firstOrNull { it.attrs["name"].equals("cover", ignoreCase = true) }
        ?.attrs?.get("content")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun ManifestItem.isImage(): Boolean =
    mediaType.startsWith("image/") || fileName(href).substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun fileName(href: String): String = href.substringAfterLast('/')

/** The first xhtml file in the spine, even one marked `linear="no"` (cover pages often are). */
private fun firstSpineFile(manifest: Map<String, ManifestItem>, spineIds: List<String>): String? =
    spineIds.firstNotNullOfOrNull { id -> manifest[id]?.takeIf { it.mediaType in CONTENT_MEDIA_TYPES }?.href }

/** The first `<img src>` or SVG `<image href>` in a page, resolved to its path in the zip. */
private fun firstImageIn(zip: ZipFile, pageHref: String): String? {
    val entry = zip.getEntry(pageHref) ?: return null
    val html = try {
        decodeHtmlBytes(zip.getInputStream(entry).use { it.readBytes() }, pageHref)
    } catch (e: EpubParseException) {
        return null // an unreadable cover page just means no cover
    }
    val src = tokenizeMarkup(html).asSequence()
        .filterIsInstance<MarkupToken.StartTag>()
        .firstNotNullOfOrNull { tag ->
            when (tag.name) {
                "img" -> tag.attrs["src"]
                "image" -> tag.attrs["xlink:href"] ?: tag.attrs["href"]
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
        }
        ?: return null
    return resolvePath(zipDirname(pageHref), src)
}

/** The file's bytes, or null if it's missing, empty or too big. */
private fun readImage(zip: ZipFile, path: String): ByteArray? {
    val entry = zip.getEntry(path) ?: return null
    if (entry.size > MAX_COVER_BYTES) return null
    val bytes = zip.getInputStream(entry).use { it.readBytes() }
    return bytes.takeIf { it.isNotEmpty() && it.size <= MAX_COVER_BYTES }
}
