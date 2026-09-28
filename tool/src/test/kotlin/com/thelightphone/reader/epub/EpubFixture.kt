package com.thelightphone.reader.epub

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds small EPUBs on disk (temp files) for tests — never real book content. */
object EpubFixture {

    data class ChapterSpec(
        val id: String,
        val filename: String,
        val bodyHtml: String,
        val navTitle: String? = null,
        val linear: Boolean = true,
    )

    fun chapter(id: String, navTitle: String, paragraphs: List<String>): ChapterSpec {
        val body = paragraphs.joinToString("") { "<p>$it</p>" }
        return ChapterSpec(id = id, filename = "$id.xhtml", bodyHtml = body, navTitle = navTitle)
    }

    /** A paragraph long enough on its own to clear MIN_CONTENT_CHARS. */
    fun longParagraph(label: String, chars: Int = MIN_CONTENT_CHARS + 50): String {
        val filler = "$label filler text. "
        return filler.repeat((chars / filler.length) + 1).take(chars)
    }

    fun build(
        title: String? = "Test Book",
        author: String? = "Test Author",
        chapters: List<ChapterSpec>,
        tocStyle: TocStyle = TocStyle.NCX,
        drm: Boolean = false,
        rawEntries: Map<String, ByteArray> = emptyMap(),
        spineIncludesNav: Boolean = false,
        /** Raw OPF metadata lines, e.g. a series `<meta>`. */
        extraMetadata: String = "",
        /** Raw manifest `<item>`s, e.g. a cover image (its bytes go in [rawEntries]). */
        extraManifest: String = "",
    ): File {
        val opfDir = "OEBPS/"
        val manifest = StringBuilder()
        val spine = StringBuilder()
        for (c in chapters) {
            manifest.append("""<item id="${c.id}" href="${c.filename}" media-type="application/xhtml+xml"/>""")
            val linearAttr = if (!c.linear) """ linear="no"""" else ""
            spine.append("""<itemref idref="${c.id}"$linearAttr/>""")
        }
        manifest.append(extraManifest)
        if (tocStyle == TocStyle.NCX) manifest.append("""<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""")
        if (tocStyle == TocStyle.NAV) {
            manifest.append("""<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
            if (spineIncludesNav) spine.append("""<itemref idref="nav"/>""")
        }

        val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    ${title?.let { "<dc:title>${escape(it)}</dc:title>" } ?: ""}
    ${author?.let { "<dc:creator>${escape(it)}</dc:creator>" } ?: ""}
    $extraMetadata
  </metadata>
  <manifest>$manifest</manifest>
  <spine${if (tocStyle == TocStyle.NCX) """ toc="ncx"""" else ""}>$spine</spine>
</package>"""

        val entries = LinkedHashMap<String, ByteArray>()
        entries["META-INF/container.xml"] = """<?xml version="1.0"?>
<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
  <rootfiles>
    <rootfile full-path="${opfDir}content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>""".toByteArray(Charsets.UTF_8)
        if (drm) entries["META-INF/encryption.xml"] = "<encryption/>".toByteArray(Charsets.UTF_8)
        entries["${opfDir}content.opf"] = opf.toByteArray(Charsets.UTF_8)
        for (c in chapters) {
            entries["$opfDir${c.filename}"] =
                "<html><body>${c.bodyHtml}</body></html>".toByteArray(Charsets.UTF_8)
        }
        if (tocStyle == TocStyle.NCX) entries["${opfDir}toc.ncx"] = buildNcx(chapters).toByteArray(Charsets.UTF_8)
        if (tocStyle == TocStyle.NAV) entries["${opfDir}nav.xhtml"] = buildNav(chapters).toByteArray(Charsets.UTF_8)
        entries.putAll(rawEntries)

        return writeZip(entries)
    }

    enum class TocStyle { NCX, NAV, NONE }

    private fun buildNcx(chapters: List<ChapterSpec>): String {
        val navPoints = chapters.filter { it.navTitle != null }.mapIndexed { i, c ->
            """<navPoint id="np${i + 1}" playOrder="${i + 1}">
                <navLabel><text>${escape(c.navTitle!!)}</text></navLabel>
                <content src="${c.filename}"/>
            </navPoint>"""
        }.joinToString("")
        return """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <navMap>$navPoints</navMap>
</ncx>"""
    }

    private fun buildNav(chapters: List<ChapterSpec>): String {
        val links = chapters.filter { it.navTitle != null }.joinToString("") {
            """<li><a href="${it.filename}">${escape(it.navTitle!!)}</a></li>"""
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
  <body>
    <nav epub:type="toc"><ol>$links</ol></nav>
  </body>
</html>"""
    }

    private fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun writeZip(entries: Map<String, ByteArray>): File {
        val file = File.createTempFile("epubfixture", ".epub")
        file.deleteOnExit()
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            for ((path, bytes) in entries) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return file
    }
}
