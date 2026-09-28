package com.thelightphone.reader.epub

import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlTextTest {

    @Test
    fun splitsParagraphsOnBlockTags() {
        assertEquals("One.\n\nTwo.", extractText("<p>One.</p><p>Two.</p>"))
    }

    @Test
    fun splitsParagraphsOnDivAndListItems() {
        assertEquals("A.\n\nB.", extractText("<div>A.</div><li>B.</li>"))
    }

    @Test
    fun selfClosedBrBreaksParagraphs() {
        assertEquals("Line one.\n\nLine two.", extractText("<p>Line one.<br/>Line two.</p>"))
    }

    @Test
    fun bareBrBreaksParagraphsToo() {
        assertEquals("Line one.\n\nLine two.", extractText("<p>Line one.<br>Line two.</p>"))
    }

    @Test
    fun dropsScriptAndStyleContent() {
        assertEquals("Visible.", extractText("<style>.x{color:red}</style><p>Visible.</p><script>alert(1)</script>"))
    }

    @Test
    fun selfClosedScriptDoesNotSwallowTheRestOfTheChapter() {
        // Some readers inject a bodyless <script .../> (e.g. Kobo's kobo.js hook). It has
        // no end tag, so it must not open a "skip everything after this" region.
        assertEquals(
            "Visible.",
            extractText("""<script xmlns="x" src="../../js/kobo.js"/><p>Visible.</p>"""),
        )
    }

    @Test
    fun selfClosedStyleDoesNotSwallowTheRestOfTheChapter() {
        assertEquals("Visible.", extractText("""<style id="x"/><p>Visible.</p>"""))
    }

    @Test
    fun collapsesInternalWhitespaceWithinAParagraph() {
        assertEquals("One long line.", extractText("<p>One   long\n  line.</p>"))
    }

    @Test
    fun decodesEntitiesInBody() {
        assertEquals("café — tea", extractText("<p>caf&eacute; &mdash; tea</p>"))
    }

    @Test
    fun ignoresTagsWithoutBlockSemantics() {
        assertEquals("Bold and plain.", extractText("<p><b>Bold</b> and plain.</p>"))
    }

    @Test
    fun emptyDocumentProducesEmptyText() {
        assertEquals("", extractText("<html><head></head><body></body></html>"))
    }
}
