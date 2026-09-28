package com.thelightphone.reader

import com.thelightphone.reader.epub.StyleRange

/**
 * How a chapter is cut into pages, in plain Kotlin so it can be unit-tested on the PC.
 * ReaderScreen measures the chapter with Compose and hands the lines to [paginate].
 */

/** One laid-out line: its top and bottom in pixels, where it starts in the text, and whether it's empty. */
data class TextLine(val top: Float, val bottom: Float, val start: Int, val isBlank: Boolean)

/**
 * A page's slice of the chapter text, in character offsets. Half-open ([start], [endExclusive])
 * so consecutive pages cover the chapter with no gap or overlap.
 */
data class PageRange(val start: Int, val endExclusive: Int) {
    operator fun contains(offset: Int) = offset in start until endExclusive
}

/**
 * Chapter text separates paragraphs with one blank line. A second blank line reads more
 * clearly on the phone, so it's added here before measuring. Saved offsets are counted in
 * this spaced-out text, as they were in the September app.
 */
fun withExtraParagraphSpacing(text: String): String = text.replace("\n\n", "\n\n\n")

/**
 * The chapter's style ranges moved to match [withExtraParagraphSpacing]: each paragraph
 * break before an offset adds one character. [text] is the chapter as stored (not spaced).
 */
fun withExtraParagraphSpacing(styles: List<StyleRange>, text: String): List<StyleRange> {
    if (styles.isEmpty()) return styles
    // Where each paragraph starts; every one after the first follows a "\n\n".
    val paragraphStarts = mutableListOf<Int>()
    var i = text.indexOf("\n\n")
    while (i >= 0) {
        paragraphStarts.add(i + 2)
        i = text.indexOf("\n\n", i + 2)
    }
    fun spaced(offset: Int): Int {
        val breaksBefore = paragraphStarts.binarySearch(offset).let { if (it >= 0) it + 1 else -it - 1 }
        return offset + breaksBefore
    }
    return styles.map { it.copy(start = spaced(it.start), end = spaced(it.end)) }
}

/**
 * Fills each page with as many whole lines as fit. The first page is shorter
 * ([firstPageHeightPx]) because the chapter heading sits above it.
 *
 * Blank lines that would open a page are kept at the bottom of the page before instead,
 * where they're invisible, so no page starts with an empty gap.
 */
fun paginate(lines: List<TextLine>, textLength: Int, pageHeightPx: Int, firstPageHeightPx: Int): List<PageRange> {
    if (lines.isEmpty()) return listOf(PageRange(0, textLength))

    val pages = mutableListOf<PageRange>()
    var first = 0
    while (first < lines.size) {
        val height = if (pages.isEmpty()) firstPageHeightPx else pageHeightPx
        val top = lines[first].top
        var last = first
        while (last + 1 < lines.size && lines[last + 1].bottom - top <= height) last++
        while (last + 1 < lines.size && lines[last + 1].isBlank) last++

        val start = if (pages.isEmpty()) 0 else lines[first].start
        val end = if (last + 1 < lines.size) lines[last + 1].start else textLength
        pages.add(PageRange(start, end))
        first = last + 1
    }
    return pages
}

/**
 * The page holding [offset]. Line breaks at the offset are skipped first, so an old saved
 * place that points at a blank line opens on the text after it. Past the end = last page.
 */
fun pageIndexFor(pages: List<PageRange>, text: String, offset: Int): Int {
    var target = offset.coerceIn(0, text.length)
    while (target < text.length && text[target] == '\n') target++
    val index = pages.indexOfFirst { target in it }
    return if (index >= 0) index else pages.lastIndex.coerceAtLeast(0)
}
