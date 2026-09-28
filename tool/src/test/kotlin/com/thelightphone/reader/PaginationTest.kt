package com.thelightphone.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PaginationTest {

    /** Builds lines 10px tall from the text, one per `\n`-separated line. */
    private fun linesOf(text: String): List<TextLine> {
        var start = 0
        return text.split("\n").mapIndexed { i, line ->
            TextLine(top = i * 10f, bottom = i * 10f + 10f, start = start, isBlank = line.isBlank())
                .also { start += line.length + 1 }
        }
    }

    @Test
    fun `pages cover the whole text with no gaps`() {
        val text = (1..10).joinToString("\n") { "line $it" }
        val pages = paginate(linesOf(text), text.length, pageHeightPx = 30, firstPageHeightPx = 30)

        assertEquals(4, pages.size)
        assertEquals(0, pages.first().start)
        assertEquals(text.length, pages.last().endExclusive)
        pages.zipWithNext().forEach { (a, b) -> assertEquals(a.endExclusive, b.start) }
    }

    @Test
    fun `first page is shorter to leave room for the heading`() {
        val text = (1..10).joinToString("\n") { "line $it" }
        val pages = paginate(linesOf(text), text.length, pageHeightPx = 50, firstPageHeightPx = 20)

        assertEquals("line 1\nline 2\n", text.substring(pages[0].start, pages[0].endExclusive))
        assertEquals("line 3", text.substring(pages[1].start, pages[1].endExclusive).lines().first())
    }

    @Test
    fun `no page starts with a blank line`() {
        val text = "a\nb\n\n\nc\nd"
        val pages = paginate(linesOf(text), text.length, pageHeightPx = 20, firstPageHeightPx = 20)

        assertEquals(listOf("a\nb\n\n\n", "c\nd"), pages.map { text.substring(it.start, it.endExclusive) })
    }

    @Test
    fun `empty chapter is one empty page`() {
        assertEquals(listOf(PageRange(0, 0)), paginate(emptyList(), 0, 100, 100))
    }

    @Test
    fun `page for an offset`() {
        val text = "a\nb\n\n\nc\nd"
        val pages = paginate(linesOf(text), text.length, pageHeightPx = 20, firstPageHeightPx = 20)

        assertEquals(0, pageIndexFor(pages, text, 0))
        assertEquals(1, pageIndexFor(pages, text, text.indexOf('c')))
        // An old saved place pointing at a blank line opens on the text after it.
        assertEquals(1, pageIndexFor(pages, text, text.indexOf("\n\n")))
        // Previous chapter's "go to the end".
        assertEquals(1, pageIndexFor(pages, text, Int.MAX_VALUE))
        assertEquals(0, pageIndexFor(pages, text, -5))
    }

    @Test
    fun `paragraph breaks get an extra blank line`() {
        assertEquals("one\n\n\ntwo\n\n\nthree", withExtraParagraphSpacing("one\n\ntwo\n\nthree"))
    }
}
