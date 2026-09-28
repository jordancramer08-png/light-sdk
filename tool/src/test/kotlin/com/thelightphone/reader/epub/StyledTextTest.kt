package com.thelightphone.reader.epub

import com.thelightphone.reader.epub.TextStyleKind.BOLD
import com.thelightphone.reader.epub.TextStyleKind.ITALIC
import com.thelightphone.reader.epub.TextStyleKind.QUOTE
import com.thelightphone.reader.epub.TextStyleKind.SCENE_BREAK
import kotlin.test.Test
import kotlin.test.assertEquals

class StyledTextTest {

    /** The text each range covers, e.g. "ITALIC:quiet". */
    private fun covered(styled: StyledText): List<String> =
        styled.styles.map { "${it.style}:${styled.text.substring(it.start, it.end)}" }

    @Test
    fun italicTagsBecomeItalicRanges() {
        val styled = extractStyledText("<p>A <i>quiet</i> day, <em>very</em> much <cite>Dune</cite>.</p>")

        assertEquals("A quiet day, very much Dune.", styled.text)
        assertEquals(listOf("ITALIC:quiet", "ITALIC:very", "ITALIC:Dune"), covered(styled))
    }

    @Test
    fun boldTagsBecomeBoldRanges() {
        val styled = extractStyledText("<p><b>Stop</b> and <strong>look</strong>.</p>")

        assertEquals("Stop and look.", styled.text)
        assertEquals(listOf("BOLD:Stop", "BOLD:look"), covered(styled))
    }

    @Test
    fun boldItalicGetsBothRanges() {
        val styled = extractStyledText("<p>Say <b><i>now</i></b>!</p>")

        assertEquals(listOf(StyleRange(ITALIC, 4, 7), StyleRange(BOLD, 4, 7)).sortedBy { it.style }, styled.styles.sortedBy { it.style })
    }

    @Test
    fun spacesAtTheEdgesOfARangeAreLeftOut() {
        val styled = extractStyledText("<p>one<i> two </i>three</p>")

        assertEquals("one two three", styled.text)
        assertEquals(listOf("ITALIC:two"), covered(styled))
    }

    @Test
    fun italicAcrossParagraphsIsSplitPerParagraph() {
        val styled = extractStyledText("<i><p>First.</p><p>Second.</p></i>")

        assertEquals("First.\n\nSecond.", styled.text)
        assertEquals(listOf("ITALIC:First.", "ITALIC:Second."), covered(styled))
    }

    @Test
    fun rangesSurviveWhitespaceCollapsing() {
        val styled = extractStyledText("<p>  lots   of\n   <em>space   here</em>  </p>")

        assertEquals("lots of space here", styled.text)
        assertEquals(listOf("ITALIC:space here"), covered(styled))
    }

    @Test
    fun horizontalRuleIsASceneBreak() {
        val styled = extractStyledText("<p>Before.</p><hr/><p>After.</p>")

        assertEquals("Before.\n\n⁂\n\nAfter.", styled.text)
        assertEquals(listOf(StyleRange(SCENE_BREAK, 9, 10)), styled.styles)
    }

    @Test
    fun asteriskParagraphsAreSceneBreaks() {
        for (mark in listOf("* * *", "***", "⁂", "#", "~", "*&nbsp;*&nbsp;*")) {
            val styled = extractStyledText("<p>Before.</p><p class=\"center\">$mark</p><p>After.</p>")
            assertEquals("Before.\n\n⁂\n\nAfter.", styled.text, "scene break written as $mark")
            assertEquals(listOf("SCENE_BREAK:⁂"), covered(styled))
        }
    }

    @Test
    fun ordinaryShortParagraphsAreNotSceneBreaks() {
        val styled = extractStyledText("<p>Before.</p><p>* Note</p><p>A</p>")

        assertEquals("Before.\n\n* Note\n\nA", styled.text)
        assertEquals(emptyList(), styled.styles)
    }

    @Test
    fun repeatedAndEdgeSceneBreaksAreDropped() {
        val styled = extractStyledText("<hr/><p>One.</p><hr/><p>* * *</p><p>Two.</p><hr/>")

        assertEquals("One.\n\n⁂\n\nTwo.", styled.text)
        assertEquals(listOf("SCENE_BREAK:⁂"), covered(styled))
    }

    @Test
    fun blockquoteIsOneQuoteRangeOverItsParagraphs() {
        val styled = extractStyledText("<p>He wrote:</p><blockquote><p>Line one.</p><p>Line <i>two</i>.</p></blockquote><p>End.</p>")

        assertEquals("He wrote:\n\nLine one.\n\nLine two.\n\nEnd.", styled.text)
        assertEquals(listOf("QUOTE:Line one.\n\nLine two.", "ITALIC:two"), covered(styled))
    }

    @Test
    fun plainTextIsUnchangedByStyles() {
        val html = "<p>A <i>b</i> c<br/>d <b>e</b></p><blockquote>f</blockquote>"

        assertEquals("A b c\n\nd e\n\nf", extractText(html))
        assertEquals(QUOTE, extractStyledText(html).styles.last().style)
    }
}
