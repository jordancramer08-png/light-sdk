package com.thelightphone.reader

import com.thelightphone.reader.data.ChapterMeta
import com.thelightphone.reader.epub.StyleRange
import com.thelightphone.reader.epub.TextStyleKind
import kotlin.test.Test
import kotlin.test.assertEquals

class ChapterTreeTest {

    private var nextIndex = 1

    private fun chapter(title: String, vararg parents: String) = ChapterMeta(
        index = nextIndex++,
        title = title,
        file = "x.txt",
        chars = 1,
        depth = parents.size,
        parents = parents.toList(),
    )

    /** "  Title (→3)" with two spaces per level, and "#" in front of a heading. */
    private fun drawn(rows: List<ContentsRow>) =
        rows.map { "  ".repeat(it.depth) + (if (it.isHeading) "# " else "") + it.title + " (→${it.chapterIndex})" }

    @Test
    fun omnibusHeadingsAppearOnceAboveTheirChapters() {
        val b1 = "BOOK 1: GARDENS OF THE MOON"
        val b2 = "BOOK 2: DEADHOUSE GATES"
        val chapters = listOf(
            chapter("Prologue", b1),
            chapter("Chapter One", b1, "Book One: Pale"),
            chapter("Chapter Two", b1, "Book One: Pale"),
            chapter("Chapter Three", b1, "Book Two: Darujhistan"),
            chapter("Chapter One", b2, "Book One: Raraku"),
            chapter("Glossary"),
        )

        assertEquals(
            listOf(
                "# BOOK 1: GARDENS OF THE MOON (→1)",
                "  Prologue (→1)",
                "  # Book One: Pale (→2)",
                "    Chapter One (→2)",
                "    Chapter Two (→3)",
                "  # Book Two: Darujhistan (→4)",
                "    Chapter Three (→4)",
                "# BOOK 2: DEADHOUSE GATES (→5)",
                "  # Book One: Raraku (→5)",
                "    Chapter One (→5)",
                "Glossary (→6)",
            ),
            drawn(contentsRows(chapters)),
        )
    }

    @Test
    fun aHeadingPageWithTextIsItsOwnRowAndNotRepeated() {
        val chapters = listOf(
            chapter("Part One"), // kept as a chapter: its page had enough text
            chapter("Chapter 1", "Part One"),
            chapter("Chapter 2", "Part One"),
        )

        assertEquals(
            listOf("# Part One (→1)", "  Chapter 1 (→2)", "  Chapter 2 (→3)"),
            drawn(contentsRows(chapters)),
        )
    }

    @Test
    fun flatBookHasNoHeadings() {
        val chapters = listOf(chapter("One"), chapter("Two"))

        assertEquals(listOf("One (→1)", "Two (→2)"), drawn(contentsRows(chapters)))
    }

    @Test
    fun barTitleShowsTheTopHeadingWhenThereIsOne() {
        assertEquals(
            "BOOK 1: GARDENS OF THE MOON · Chapter One",
            readerBarTitle(chapter("Chapter One", "BOOK 1: GARDENS OF THE MOON", "Book One: Pale")),
        )
        assertEquals("Glossary", readerBarTitle(chapter("Glossary")))
    }

    @Test
    fun styleRangesFollowTheExtraParagraphSpacing() {
        val text = "Ab cd\n\nEf\n\nGh"
        val styles = listOf(
            StyleRange(TextStyleKind.ITALIC, 3, 5), // "cd"
            StyleRange(TextStyleKind.QUOTE, 7, 13), // "Ef\n\nGh"
        )

        val spacedText = withExtraParagraphSpacing(text)
        val spaced = withExtraParagraphSpacing(styles, text)

        assertEquals("cd", spacedText.substring(spaced[0].start, spaced[0].end))
        assertEquals("Ef\n\n\nGh", spacedText.substring(spaced[1].start, spaced[1].end))
    }
}
