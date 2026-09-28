package com.thelightphone.reader.epub

import kotlin.test.Test
import kotlin.test.assertEquals

/** Chapters keep their place in a nested NCX or nav contents (depth + the titles above them). */
class NestedContentsTest {

    private fun readable(id: String) =
        EpubFixture.ChapterSpec(id, "$id.xhtml", "<p>${EpubFixture.longParagraph(id)}</p>")

    /** A heading page: too little text to be a chapter. */
    private fun headingPage(id: String, title: String) =
        EpubFixture.ChapterSpec(id, "$id.xhtml", "<h1>$title</h1>")

    private fun ncx(navPoints: String) = """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><navMap>$navPoints</navMap></ncx>"""

    private fun point(title: String, src: String?, children: String = "") =
        "<navPoint><navLabel><text>$title</text></navLabel>${src?.let { "<content src=\"$it\"/>" } ?: ""}$children</navPoint>"

    private fun nav(list: String) = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
<nav epub:type="toc"><h1>Contents</h1><ol>$list</ol></nav></body></html>"""

    private fun summary(book: Book) = book.chapters.map { "${it.depth} ${it.parents.joinToString(" > ")} | ${it.title}" }

    @Test
    fun omnibusNcxKeepsBooksAndPartsAboveChapters() {
        val file = EpubFixture.build(
            chapters = listOf(
                headingPage("cover", "Cover"),
                headingPage("b1", "Book 1"),
                readable("prologue"),
                headingPage("b1p1", "Part"),
                readable("c1"), readable("c2"),
                headingPage("b2", "Book 2"),
                headingPage("b2p1", "Part"),
                readable("c3"),
                readable("epilogue"),
            ),
            rawEntries = mapOf(
                "OEBPS/toc.ncx" to ncx(
                    point("Cover", "cover.xhtml") +
                        point(
                            "BOOK 1: GARDENS OF THE MOON", "b1.xhtml",
                            point("Prologue", "prologue.xhtml") +
                                point("Book One: Pale", "b1p1.xhtml", point("Chapter One", "c1.xhtml") + point("Chapter Two", "c2.xhtml#top")),
                        ) +
                        point(
                            "BOOK 2: DEADHOUSE GATES", "b2.xhtml",
                            point("Book One: Raraku", "b2p1.xhtml", point("Chapter One", "c3.xhtml")),
                        ) +
                        point("Epilogue", "epilogue.xhtml"),
                ).toByteArray(),
            ),
        )

        val book = EpubParser.parse(file)

        assertEquals(
            listOf(
                "1 BOOK 1: GARDENS OF THE MOON | Prologue",
                "2 BOOK 1: GARDENS OF THE MOON > Book One: Pale | Chapter One",
                "2 BOOK 1: GARDENS OF THE MOON > Book One: Pale | Chapter Two",
                "2 BOOK 2: DEADHOUSE GATES > Book One: Raraku | Chapter One",
                "0  | Epilogue",
            ),
            summary(book),
        )
    }

    @Test
    fun ncxHeadingWithoutALinkStillCountsAsAParent() {
        val file = EpubFixture.build(
            chapters = listOf(readable("c1")),
            rawEntries = mapOf("OEBPS/toc.ncx" to ncx(point("Part One", null, point("Chapter 1", "c1.xhtml"))).toByteArray()),
        )

        assertEquals(listOf("1 Part One | Chapter 1"), summary(EpubParser.parse(file)))
    }

    @Test
    fun ncxParentTitlesAreRepairedLikeChapterTitles() {
        val file = EpubFixture.build(
            chapters = listOf(readable("c1")),
            rawEntries = mapOf("OEBPS/toc.ncx" to ncx(point("PARTIII", null, point("theEnd", "c1.xhtml"))).toByteArray()),
        )

        assertEquals(listOf("1 PART III | the End"), summary(EpubParser.parse(file)))
    }

    @Test
    fun nestedNavKeepsItsLevels() {
        val file = EpubFixture.build(
            chapters = listOf(headingPage("p1", "Part One"), readable("c1"), readable("c2"), readable("c3")),
            tocStyle = EpubFixture.TocStyle.NAV,
            rawEntries = mapOf(
                "OEBPS/nav.xhtml" to nav(
                    """<li><a href="p1.xhtml">Part One</a><ol>
                         <li><a href="c1.xhtml">One</a></li>
                         <li><span>Interludes</span><ol><li><a href="c2.xhtml#x">Two</a></li></ol></li>
                       </ol></li>
                       <li><a href="c3.xhtml">Three</a></li>""",
                ).toByteArray(),
            ),
        )

        assertEquals(
            listOf("1 Part One | One", "2 Part One > Interludes | Two", "0  | Three"),
            summary(EpubParser.parse(file)),
        )
    }

    @Test
    fun fileMissingFromTheContentsJoinsTheEntryBeforeIt() {
        val file = EpubFixture.build(
            chapters = listOf(headingPage("p1", "Part One"), readable("x1"), readable("c1"), readable("x2")),
            rawEntries = mapOf("OEBPS/toc.ncx" to ncx(point("Part One", "p1.xhtml", point("One", "c1.xhtml"))).toByteArray()),
        )

        // x1 follows the Part One heading page: it goes under it. x2 follows chapter One: beside it.
        assertEquals(
            listOf("1 Part One | Chapter 1", "1 Part One | One", "1 Part One | Chapter 3"),
            summary(EpubParser.parse(file)),
        )
    }

    @Test
    fun unlistedTextAfterADividerPageTakesTheDividersTitle() {
        val file = EpubFixture.build(
            chapters = listOf(
                headingPage("cover", "Cover"), readable("x0"),
                headingPage("pro", "Prologue"), readable("x1"),
                headingPage("pro2", "Prologue"), headingPage("gap", ""), readable("x2"),
            ),
            rawEntries = mapOf(
                "OEBPS/toc.ncx" to ncx(
                    point("Cover", "cover.xhtml") + point("Prologue", "pro.xhtml") + point("Interlude", "pro2.xhtml"),
                ).toByteArray(),
            ),
        )

        // x0 follows the dropped Cover (front matter): numbered. x1 follows the Prologue divider: named after it.
        // x2 is two files past the Interlude divider: numbered.
        assertEquals(
            listOf("Chapter 1", "Prologue", "Chapter 3"),
            EpubParser.parse(file).chapters.map { it.title },
        )
    }

    @Test
    fun flatContentsHasNoParents() {
        val file = EpubFixture.build(
            chapters = listOf(
                EpubFixture.chapter("c1", "One", listOf(EpubFixture.longParagraph("a"))),
                EpubFixture.chapter("c2", "Two", listOf(EpubFixture.longParagraph("b"))),
            ),
        )

        assertEquals(listOf("0  | One", "0  | Two"), summary(EpubParser.parse(file)))
    }

    @Test
    fun chapterStylesComeThroughTheParser() {
        val body = "<p>${EpubFixture.longParagraph("a")}</p><hr/><p>An <i>italic</i> word, ${EpubFixture.longParagraph("b")}</p>"
        val file = EpubFixture.build(chapters = listOf(EpubFixture.ChapterSpec("c1", "c1.xhtml", body, navTitle = "One")))

        val chapter = EpubParser.parse(file).chapters.single()

        assertEquals(
            listOf(TextStyleKind.SCENE_BREAK, TextStyleKind.ITALIC),
            chapter.styles.map { it.style },
        )
        val italic = chapter.styles.last()
        assertEquals("italic", chapter.text.substring(italic.start, italic.end))
    }
}
