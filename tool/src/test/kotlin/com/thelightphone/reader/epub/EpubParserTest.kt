package com.thelightphone.reader.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EpubParserTest {

    private fun paragraphs(n: Int): List<String> = (1..n).map { EpubFixture.longParagraph("Paragraph $it") }

    @Test
    fun parsesTitleAuthorAndChapterTitlesFromNcx() {
        val file = EpubFixture.build(
            title = "The Long Way",
            author = "Ann Author",
            chapters = listOf(
                EpubFixture.chapter("c1", "Chapter One", paragraphs(2)),
                EpubFixture.chapter("c2", "Chapter Two", paragraphs(2)),
            ),
        )

        val book = EpubParser.parse(file)

        assertEquals("The Long Way", book.title)
        assertEquals("Ann Author", book.author)
        assertEquals(listOf("Chapter One", "Chapter Two"), book.chapters.map { it.title })
    }

    @Test
    fun fallsBackToNavXhtmlWhenNoNcx() {
        val file = EpubFixture.build(
            chapters = listOf(EpubFixture.chapter("c1", "From Nav", paragraphs(2))),
            tocStyle = EpubFixture.TocStyle.NAV,
        )

        val book = EpubParser.parse(file)

        assertEquals(listOf("From Nav"), book.chapters.map { it.title })
    }

    @Test
    fun untitledChaptersAreNumbered() {
        val file = EpubFixture.build(
            chapters = listOf(
                EpubFixture.ChapterSpec("c1", "c1.xhtml", "<p>${EpubFixture.longParagraph("A")}</p>"),
                EpubFixture.ChapterSpec("c2", "c2.xhtml", "<p>${EpubFixture.longParagraph("B")}</p>"),
            ),
            tocStyle = EpubFixture.TocStyle.NONE,
        )

        val book = EpubParser.parse(file)

        assertEquals(listOf("Chapter 1", "Chapter 2"), book.chapters.map { it.title })
    }

    @Test
    fun missingTitleAndAuthorFallBackToDefaults() {
        val file = EpubFixture.build(
            title = null,
            author = null,
            chapters = listOf(EpubFixture.chapter("c1", "One", paragraphs(2))),
        )

        val book = EpubParser.parse(file)

        assertEquals("Untitled", book.title)
        assertEquals("Unknown", book.author)
    }

    @Test
    fun filtersOutShortFrontMatterChapters() {
        val file = EpubFixture.build(
            chapters = listOf(
                EpubFixture.chapter("cover", "Cover", listOf("Tiny cover blurb.")),
                EpubFixture.chapter("c1", "Chapter One", paragraphs(2)),
            ),
        )

        val book = EpubParser.parse(file)

        assertEquals(listOf("Chapter One"), book.chapters.map { it.title })
    }

    @Test
    fun skipsNonLinearSpineItems() {
        val file = EpubFixture.build(
            chapters = listOf(
                EpubFixture.chapter("c1", "Chapter One", paragraphs(2)),
                EpubFixture.ChapterSpec("ad", "ad.xhtml", "<p>${EpubFixture.longParagraph("Ad")}</p>", linear = false),
            ),
        )

        val book = EpubParser.parse(file)

        assertEquals(listOf("Chapter One"), book.chapters.map { it.title })
    }

    @Test
    fun skipsTheNavManifestItemEvenIfInSpine() {
        val file = EpubFixture.build(
            chapters = listOf(EpubFixture.chapter("c1", "Chapter One", paragraphs(2))),
            tocStyle = EpubFixture.TocStyle.NAV,
            spineIncludesNav = true,
        )

        val book = EpubParser.parse(file)

        assertEquals(listOf("Chapter One"), book.chapters.map { it.title })
    }

    @Test
    fun drmProtectedBookThrowsDrmException() {
        val file = EpubFixture.build(
            chapters = listOf(EpubFixture.chapter("c1", "Chapter One", paragraphs(2))),
            drm = true,
        )

        assertFailsWith<DrmProtectedException> { EpubParser.parse(file) }
    }

    @Test
    fun bookWithNoReadableChaptersThrows() {
        val file = EpubFixture.build(
            chapters = listOf(EpubFixture.chapter("cover", "Cover", listOf("Too short."))),
        )

        assertFailsWith<InvalidEpubException> { EpubParser.parse(file) }
    }

    @Test
    fun cp1252FallbackDecodesWhenNotValidUtf8() {
        // 0xE9 for "é" is a byte sequence that's invalid on its own as UTF-8, so this
        // exercises the windows-1252 fallback rather than the UTF-8 or declared paths.
        val bodyBytes = "<html><body><p>${EpubFixture.longParagraph("café ")}</p></body></html>"
            .toByteArray(Charsets.ISO_8859_1)
        val file = EpubFixture.build(
            chapters = listOf(EpubFixture.chapter("c1", "Chapter One", paragraphs(2))),
            rawEntries = mapOf("OEBPS/c1.xhtml" to bodyBytes),
        )

        val book = EpubParser.parse(file)

        assertTrue(book.chapters.single().text.contains("café"))
    }

    @Test
    fun repairTitleFixesFusedLowerUpperSeam() {
        assertEquals("the Olympic Games", repairTitle("theOlympic Games"))
    }

    @Test
    fun repairTitleSplitsCapsWordFromTrailingRomanNumeral() {
        assertEquals("PART III", repairTitle("PARTIII"))
    }

    @Test
    fun repairTitleLeavesShortWordsAlone() {
        assertEquals("SIX", repairTitle("SIX"))
        assertEquals("XVI", repairTitle("XVI"))
    }

    @Test
    fun slugifyLowercasesAndDashesNonAlphanumerics() {
        assertEquals("the-shadow-of-the-wind", slugify("The Shadow of the Wind"))
    }

    @Test
    fun slugifyCollapsesRepeatedSeparatorsAndTrims() {
        assertEquals("a-b", slugify("  A -- B!! "))
    }

    @Test
    fun slugifyOfEmptyTitleFallsBackToUntitled() {
        assertEquals("untitled", slugify("***"))
    }
}
