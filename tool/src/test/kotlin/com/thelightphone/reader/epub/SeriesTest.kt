package com.thelightphone.reader.epub

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeriesTest {

    private fun opf(metadata: String) =
        """<package><metadata xmlns:dc="x"><dc:title>T</dc:title>$metadata</metadata></package>"""

    @Test
    fun readsCalibreSeries() {
        val text = opf(
            """<meta name="calibre:series" content="Cemetery of Forgotten Books"/>
               <meta name="calibre:series_index" content="1.0"/>""",
        )

        assertEquals(Series("Cemetery of Forgotten Books", "1"), readSeries(text))
    }

    @Test
    fun readsEpub3Collection() {
        val text = opf(
            """<meta property="belongs-to-collection" id="c01">The Expanse</meta>
               <meta refines="#c01" property="collection-type">series</meta>
               <meta refines="#c01" property="group-position">3</meta>""",
        )

        assertEquals(Series("The Expanse", "3"), readSeries(text))
    }

    @Test
    fun calibreWinsOverEpub3() {
        val text = opf(
            """<meta property="belongs-to-collection" id="c1">Other</meta>
               <meta name="calibre:series" content="Main"/>""",
        )

        assertEquals(Series("Main", null), readSeries(text))
    }

    @Test
    fun noSeriesGivesNull() {
        assertNull(readSeries(opf("")))
    }

    @Test
    fun readsSeriesFromFileName() {
        assertEquals(
            Series("Cemetery of Forgotten Books", "1"),
            seriesFromFileName("Cemetery of Forgotten Books 01. The Shadow of the Wind - Carlos Ruiz Zafon.epub"),
        )
        assertEquals(Series("Discworld", "12"), seriesFromFileName("Discworld 12. Witches Abroad - Terry Pratchett.epub"))
    }

    @Test
    fun standaloneFileNameHasNoSeries() {
        assertNull(seriesFromFileName("The Road - Cormac McCarthy.epub"))
        assertNull(seriesFromFileName("Catch-22 - Joseph Heller.epub"))
        assertNull(seriesFromFileName("book.epub"))
    }

    @Test
    fun tidiesNumbers() {
        assertEquals("1", tidySeriesNumber("01"))
        assertEquals("1", tidySeriesNumber("1.0"))
        assertEquals("2.5", tidySeriesNumber("2.50"))
        assertEquals("IV", tidySeriesNumber(" IV "))
        assertNull(tidySeriesNumber(""))
        assertNull(tidySeriesNumber(null))
    }

    @Test
    fun parserUsesMetadataThenFileName() {
        val chapters = listOf(EpubFixture.chapter("c1", "One", listOf(EpubFixture.longParagraph("x"))))
        val dir = Files.createTempDirectory("series").toFile()
        try {
            val withMeta = File(dir, "Wrong 09. Sea Story - Ann Author.epub")
            EpubFixture.build(
                chapters = chapters,
                extraMetadata = """<meta name="calibre:series" content="Right"/><meta name="calibre:series_index" content="4"/>""",
            ).copyTo(withMeta)
            val fromName = File(dir, "Sea Tales 02. Sea Story - Ann Author.epub")
            EpubFixture.build(chapters = chapters).copyTo(fromName)

            assertEquals("Right" to "4", EpubParser.parse(withMeta).let { it.series to it.seriesNumber })
            assertEquals("Sea Tales" to "2", EpubParser.parse(fromName).let { it.series to it.seriesNumber })
        } finally {
            dir.deleteRecursively()
        }
    }
}
