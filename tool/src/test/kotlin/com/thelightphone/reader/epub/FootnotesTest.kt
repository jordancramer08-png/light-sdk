package com.thelightphone.reader.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Note markers in the text, and the notes they point to, in the shapes real EPUBs use. */
class FootnotesTest {

    // --- finding markers in one chapter ------------------------------------------

    /** Each marker as "text:href". */
    private fun markers(html: String): List<String> {
        val styled = extractStyledText(html)
        return styled.noteRefs.map { "${styled.text.substring(it.start, it.end)}:${it.href}" }
    }

    @Test
    fun noterefLinksAreMarkers() {
        assertEquals(listOf("1:notes.xhtml#n1"), markers("""<p>A claim.<a epub:type="noteref" href="notes.xhtml#n1">1</a></p>"""))
        assertEquals(listOf("2:#fn2"), markers("""<p>A claim.<a role="doc-noteref" href="#fn2">2</a></p>"""))
    }

    @Test
    fun shortLinksInSupOrSmallAreMarkers() {
        assertEquals(listOf("1:#n1"), markers("""<p>One.<sup><a href="#n1">1</a></sup></p>"""))
        assertEquals(listOf("2:#n2"), markers("""<p>Two.<a href="#n2"><sup><span>2</span></sup></a></p>"""))
        assertEquals(listOf("3:#n3"), markers("""<p>Three.<small><a href="#n3">3</a></small></p>"""))
        // An empty anchor beside the link (a common converter habit) changes nothing.
        assertEquals(listOf("4:e.html#n4"), markers("""<p>Four.<sup><a id="r4"></a><a href="e.html#n4">4</a></sup></p>"""))
    }

    @Test
    fun markerKeepsOnlyItsNumber() {
        val styled = extractStyledText("""<p>Said so.<a epub:type="noteref" href="#n1">[12]</a> Then.</p>""")

        assertEquals("Said so.12 Then.", styled.text)
        assertEquals(8 to 10, styled.noteRefs.single().let { it.start to it.end })
        assertTrue(styled.noteRefs.single().explicit)
    }

    @Test
    fun ordinaryLinksAreNotMarkers() {
        assertEquals(emptyList(), markers("""<p>See <a href="#ch2">2</a>.</p>""")) // not raised
        assertEquals(emptyList(), markers("""<p>See <sup><a href="#ch2">Chapter Two</a></sup>.</p>""")) // too long
        assertEquals(emptyList(), markers("""<p>See <sup><a href="http://x.com/#a">1</a></sup>.</p>""")) // another site
        assertEquals(emptyList(), markers("""<p>See <sup><a href="notes.xhtml">1</a></sup>.</p>""")) // no id
        assertEquals(emptyList(), markers("""<p>Back<sup><a role="doc-backlink" href="#r1">1</a></sup></p>"""))
        // An ordinary link's words stay in the text, styles and all.
        assertEquals("See Chapter Two.", extractStyledText("""<p>See <a href="#ch2"><i>Chapter</i> Two</a>.</p>""").text)
    }

    @Test
    fun footnoteAsidesAreLeftOutOfTheText() {
        val styled = extractStyledText(
            """<p>A claim.<a epub:type="noteref" href="#fn1">1</a></p>""" +
                """<aside epub:type="footnote" id="fn1"><p>The source.</p><aside>inner</aside></aside><p>Next.</p>""",
        )

        assertEquals("A claim.1\n\nNext.", styled.text)
    }

    @Test
    fun footnoteBlocksAreLeftOutButEndnoteListsStay() {
        val footnotes = extractStyledText(
            """<p>Text.</p><div epub:type="footnotes"><p><a href="next.xhtml">Skip Notes</a></p>""" +
                """<div epub:type="footnote" id="f1"><p>Hidden.</p></div></div>""",
        )
        val endnotes = extractStyledText("""<section epub:type="endnotes"><ol><li id="n1"><p>Kept.</p></li></ol></section>""")

        assertEquals("Text.", footnotes.text)
        assertEquals("Kept.", endnotes.text)
    }

    // --- matching markers to notes, across the book --------------------------------

    private fun chapterSpec(id: String, body: String, title: String? = id) =
        EpubFixture.ChapterSpec(id, "$id.xhtml", body, navTitle = title)

    /** A chapter long enough to keep, with [markup] at its end. */
    private fun textWith(markup: String) = "<p>${EpubFixture.longParagraph("text")}</p><p>A claim.$markup</p>"

    /** Each marker and its note, as "marker=note", for the book's chapter [index]. */
    private fun notes(book: Book, index: Int = 0): List<String> {
        val chapter = book.chapters[index]
        return chapter.styles.filter { it.style == TextStyleKind.NOTE }
            .map { "${chapter.text.substring(it.start, it.end)}=${it.note}" }
    }

    @Test
    fun endnoteWithTheIdOnItsBacklink() {
        // Pax, Rubicon: <p><a id="n1" href="back">1</a> The note.</p>, in a Notes file too short to keep.
        val book = EpubParser.parse(
            EpubFixture.build(
                chapters = listOf(
                    chapterSpec("c1", textWith("""<a href="notes.xhtml#n1"><sup>1</sup></a> Again.<a href="notes.xhtml#n2"><sup>2</sup></a>""")),
                    chapterSpec(
                        "notes",
                        """<h2>Notes</h2><p><a id="n1" href="c1.xhtml#r1">1</a>&#160; Smith, <i>History</i>, p. 4.</p>""" +
                            """<p><a id="n2" href="c1.xhtml#r2">2</a> <i>Ibid.</i></p>""",
                        "Notes",
                    ),
                ),
            ),
        )

        assertEquals(1, book.chapters.size) // the Notes file itself isn't kept
        assertEquals(listOf("1=Smith, History, p. 4.", "2=Ibid."), notes(book)) // a one-word note is still a note
    }

    @Test
    fun endnoteWithAnEmptyAnchorBeforeItsNumber() {
        // God's War: <p><strong><a id="en1"></a><a href="back">1</a></strong>. The note.</p>
        val book = EpubParser.parse(
            EpubFixture.build(
                chapters = listOf(
                    chapterSpec("c1", textWith("""<sup><a id="fn1"></a><a href="en.xhtml#en1">1</a></sup>""")),
                    chapterSpec("en", """<p class="note"><strong><a id="en1"></a><a href="c1.xhtml#fn1">1</a></strong>. Letters, ii, 51.</p>"""),
                ),
            ),
        )

        assertEquals(listOf("1=Letters, ii, 51."), notes(book))
    }

    @Test
    fun endnoteInAListItemOrADefinitionList() {
        val book = EpubParser.parse(
            EpubFixture.build(
                chapters = listOf(
                    chapterSpec("c1", textWith("""<a epub:type="noteref" href="en.xhtml#n1">1</a> and<a epub:type="noteref" href="en.xhtml#n2">2</a>""")),
                    chapterSpec(
                        "en",
                        """<ol><li id="n1" epub:type="endnote"><p>First note.</p><p>Second paragraph.</p></li></ol>""" +
                            """<dl><dt id="n2">2.</dt><dd>Defined note.</dd></dl>""",
                    ),
                ),
            ),
        )

        assertEquals(listOf("1=First note.\n\nSecond paragraph.", "2=Defined note."), notes(book))
    }

    @Test
    fun footnoteInAnAsideInTheSameFile() {
        val book = EpubParser.parse(
            EpubFixture.build(
                chapters = listOf(
                    chapterSpec(
                        "c1",
                        textWith("""<a epub:type="noteref" href="#fn1">*</a>""") +
                            """<aside epub:type="footnote" id="fn1"><p><a role="doc-backlink" href="#r1">*</a> An aside.</p></aside>""",
                    ),
                ),
            ),
        )

        assertEquals(listOf("*=An aside."), notes(book))
        assertFalse("An aside" in book.chapters[0].text)
    }

    @Test
    fun notesAtTheEndOfTheChapterAndTheirBacklinks() {
        // The note's own raised number links back up to the text: that's not a marker with a note.
        val book = EpubParser.parse(
            EpubFixture.build(
                chapters = listOf(
                    chapterSpec(
                        "c1",
                        textWith("""<sup><a id="r1" href="#n1">1</a></sup>""") +
                            """<p id="n1"><sup><a href="#r1">1</a></sup> Said in 1802.</p>""",
                    ),
                ),
            ),
        )

        assertEquals(listOf("1=Said in 1802."), notes(book))
    }

    @Test
    fun noteFileOutsideTheSpineIsStillFound() {
        val notesFile = """<html><body><p id="n1">1. Out of the reading order.</p></body></html>"""
        val book = EpubParser.parse(
            EpubFixture.build(
                chapters = listOf(chapterSpec("c1", textWith("""<sup><a href="extra/Notes%20File.xhtml#n1">1</a></sup>"""))),
                rawEntries = mapOf("OEBPS/extra/Notes File.xhtml" to notesFile.toByteArray()),
            ),
        )

        assertEquals(listOf("1=Out of the reading order."), notes(book))
    }

    @Test
    fun markerWithoutAFoundNoteStaysPlainText() {
        val book = EpubParser.parse(
            EpubFixture.build(chapters = listOf(chapterSpec("c1", textWith("""<sup><a href="#missing">7</a></sup>""")))),
        )

        assertEquals(emptyList(), notes(book))
        assertTrue(book.chapters[0].text.endsWith("A claim.7"))
    }
}
