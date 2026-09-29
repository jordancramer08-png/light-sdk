package com.thelightphone.reader.comics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComicDetailsTest {

    /** What the file-name parser should find: series, issue, volume, story title, year, month, notes. */
    private data class Expected(
        val series: String?,
        val issue: String? = null,
        val year: Int? = null,
        val notes: List<String> = emptyList(),
        val volume: String? = null,
        val title: String? = null,
        val month: Int? = null,
    )

    private fun assertName(fileName: String, expected: Expected) {
        val d = parseComicFileName(fileName)
        val found = Expected(d.series, d.issue, d.year, d.notes, d.volume, d.title, d.month)
        assertEquals(expected, found, fileName)
    }

    // --- the file name: 20 names like the ones in D:\Comics ---------------------------------

    @Test
    fun `reading-order number, series, issue, year and a note`() {
        assertName(
            "00001. Action Comics #1 (1938) (First appearance of Superman, Lois Lane).cbz",
            Expected("Action Comics", "1", 1938, listOf("First appearance of Superman, Lois Lane")),
        )
    }

    @Test
    fun `plain series, issue and year`() {
        assertName("00002. Action Comics #2 (1938).cbz", Expected("Action Comics", "2", 1938))
    }

    @Test
    fun `several notes keep their order`() {
        assertName(
            "00210. Showcase #4 (1956) (First Silver Age Flash) (Barry Allen).cbz",
            Expected("Showcase", "4", 1956, listOf("First Silver Age Flash", "Barry Allen")),
        )
    }

    @Test
    fun `a note with its own brackets inside`() {
        assertName(
            "00027. Detective Comics #27 (1939) (First appearance of Batman (Bruce Wayne)).cbz",
            Expected("Detective Comics", "27", 1939, listOf("First appearance of Batman (Bruce Wayne)")),
        )
    }

    @Test
    fun `a reading-order number with a letter`() {
        assertName("00102a. Superman #1 (1939).cbz", Expected("Superman", "1", 1939))
    }

    @Test
    fun `no reading-order number`() {
        assertName("Action Comics #1000 (2018).cbz", Expected("Action Comics", "1000", 2018))
    }

    @Test
    fun `issue zero and leading zeros`() {
        assertName("00400. Wonder Woman #0 (1994).cbz", Expected("Wonder Woman", "0", 1994))
        assertName("Batman #001 (1940).cbz", Expected("Batman", "1", 1940))
    }

    @Test
    fun `half issues`() {
        assertName("01 - Wizard #1.5 (1996).cbz", Expected("Wizard", "1.5", 1996))
    }

    @Test
    fun `a series with a dash in it`() {
        assertName("Superman - The Man of Steel #1 (1991).cbz", Expected("Superman - The Man of Steel", "1", 1991))
    }

    @Test
    fun `a story title after the issue`() {
        assertName(
            "00150. Batman #1 - The Legend of the Batman (1940).cbz",
            Expected("Batman", "1", 1940, title = "The Legend of the Batman"),
        )
    }

    @Test
    fun `a graphic novel with no issue`() {
        assertName("Batman - Year One (1987).cbz", Expected("Batman - Year One", null, 1987))
    }

    @Test
    fun `volume written as v2 or Vol 2`() {
        assertName("Batman v2 #1 (2011).cbz", Expected("Batman", "1", 2011, volume = "2"))
        assertName("Green Lantern Vol. 3 #48 (1994).cbz", Expected("Green Lantern", "48", 1994, volume = "3"))
    }

    @Test
    fun `issue with no hash, and how many issues`() {
        assertName(
            "Crisis on Infinite Earths 01 (of 12) (1985).cbz",
            Expected("Crisis on Infinite Earths", "1", 1985, listOf("Issue 1 of 12")),
        )
    }

    @Test
    fun `a number that is part of the series stays`() {
        assertName("2000 AD #1 (1977).cbz", Expected("2000 AD", "1", 1977))
        assertName("Spider-Man 2099 #1 (1992).cbz", Expected("Spider-Man 2099", "1", 1992))
    }

    @Test
    fun `a month and year`() {
        assertName("Detective Comics #27 (May 1939).cbz", Expected("Detective Comics", "27", 1939, month = 5))
        assertName("Action Comics #1 (1938-06).cbz", Expected("Action Comics", "1", 1938, month = 6))
    }

    @Test
    fun `a year range gives its first year`() {
        assertName("00010. All-Star Comics #3 (1940-41).cbz", Expected("All-Star Comics", "3", 1940))
    }

    @Test
    fun `a season gives the year only`() {
        assertName("Batman #1 (Spring 1940).cbz", Expected("Batman", "1", 1940))
    }

    @Test
    fun `scanners' tags are left out`() {
        assertName("Amazing Fantasy #15 (1962) (digital) [Zone-Empire].CBZ", Expected("Amazing Fantasy", "15", 1962))
    }

    @Test
    fun `a note with punctuation inside`() {
        assertName(
            "00321. Justice League of America #21 (1963) (Crisis on Earth-One!, part 1).cbz",
            Expected("Justice League of America", "21", 1963, listOf("Crisis on Earth-One!, part 1")),
        )
    }

    @Test
    fun `no year at all`() {
        assertName("Superman Annual #1.cbz", Expected("Superman Annual", "1"))
    }

    @Test
    fun `every value from the file name says so`() {
        val d = parseComicFileName("00001. Action Comics #1 (1938) (First appearance).cbz")
        assertEquals(
            mapOf(
                ComicField.SERIES to DetailsSource.FILE_NAME,
                ComicField.ISSUE to DetailsSource.FILE_NAME,
                ComicField.DATE to DetailsSource.FILE_NAME,
                ComicField.NOTES to DetailsSource.FILE_NAME,
            ),
            d.source,
        )
    }

    // --- ComicInfo.xml -------------------------------------------------------------------------

    private val comicInfo = """
        <?xml version="1.0" encoding="utf-8"?>
        <ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <Title>Superman, Champion of the Oppressed</Title>
          <Series>Action Comics</Series>
          <Number>001</Number>
          <Volume>1938</Volume>
          <Summary>The Man of Steel &amp; Lois Lane debut.</Summary>
          <Year>1938</Year>
          <Month>6</Month>
          <Writer>Jerry Siegel</Writer>
          <Penciller>Joe Shuster, Paul Cassidy</Penciller>
          <Inker>Joe Shuster</Inker>
          <Colorist></Colorist>
          <Publisher>DC Comics</Publisher>
          <PageCount>68</PageCount>
        </ComicInfo>
    """.trimIndent()

    @Test
    fun `ComicInfo gives every field it has`() {
        val d = parseComicInfo(comicInfo)
        assertEquals("Action Comics", d.series)
        assertEquals("1", d.issue)
        assertEquals("1938", d.volume)
        assertEquals("Superman, Champion of the Oppressed", d.title)
        assertEquals(1938, d.year)
        assertEquals(6, d.month)
        assertEquals("Jerry Siegel", d.writer)
        assertEquals(listOf("Joe Shuster", "Paul Cassidy"), d.artists)
        assertEquals("DC Comics", d.publisher)
        assertEquals("The Man of Steel & Lois Lane debut.", d.summary)
        assertEquals(68, d.pageCount)
        assertEquals(DetailsSource.COMIC_INFO, d.source[ComicField.WRITER])
        assertNull(d.source[ComicField.NOTES])
    }

    @Test
    fun `ComicInfo's unknowns count as missing`() {
        val d = parseComicInfo("<ComicInfo><Series> </Series><Year>-1</Year><Month>6</Month><Volume>-1</Volume><PageCount>0</PageCount></ComicInfo>")
        assertNull(d.series)
        assertNull(d.year)
        assertNull(d.month) // a month with no year isn't kept
        assertNull(d.volume)
        assertNull(d.pageCount)
        assertEquals(emptyMap(), d.source)
    }

    @Test
    fun `not ComicInfo at all gives nothing`() {
        assertEquals(ComicDetails(), parseComicInfo("this is not xml"))
    }

    @Test
    fun `ComicInfo is found at the top first, else in a folder`() {
        assertEquals("ComicInfo.xml", comicInfoEntry(listOf("inner/ComicInfo.xml", "001.jpg", "ComicInfo.xml")))
        assertEquals("inner/comicinfo.XML", comicInfoEntry(listOf("__MACOSX/inner/ComicInfo.xml", "inner/comicinfo.XML")))
        assertNull(comicInfoEntry(listOf("001.jpg")))
    }

    // --- the details file -------------------------------------------------------------------------

    @Test
    fun `details file gives its values and ignores what it doesn't know`() {
        val d = parseDetailsSidecar(
            """{"version":1,"series":"Action Comics","issue":"01","year":1938,"month":6,
               "writer":"Jerry Siegel","artists":["Joe Shuster"," "],"publisher":"DC Comics",
               "summary":"","lookedUpAt":"2026-10-01"}""",
        )!!
        assertEquals("Action Comics", d.series)
        assertEquals("1", d.issue)
        assertEquals(listOf("Joe Shuster"), d.artists)
        assertNull(d.summary)
        assertEquals(DetailsSource.SIDECAR, d.source[ComicField.SERIES])
    }

    @Test
    fun `a broken details file is ignored`() {
        assertNull(parseDetailsSidecar("{not json"))
    }

    @Test
    fun `details file name sits beside the comic`() {
        assertEquals("00001. Action Comics #1 (1938).details.json", detailsSidecarName("00001. Action Comics #1 (1938).cbz"))
    }

    // --- priority order -------------------------------------------------------------------------

    @Test
    fun `each value comes from the best place that has it`() {
        val sidecar = """{"series":"Action Comics (1938)","summary":"From the lookup."}"""
        val fileName = "00001. Action Comics #1 (1938) (First appearance of Superman).cbz"
        val d = comicDetails(sidecar, comicInfo, fileName, pageCount = 66)

        assertEquals("Action Comics (1938)", d.series) // details file beats ComicInfo and the name
        assertEquals("From the lookup.", d.summary)
        assertEquals("1", d.issue) // ComicInfo, as the details file has none
        assertEquals("Jerry Siegel", d.writer)
        assertEquals(68, d.pageCount) // ComicInfo's count beats the pages counted
        assertEquals(listOf("First appearance of Superman"), d.notes) // only the name has notes
        assertEquals(DetailsSource.SIDECAR, d.source[ComicField.SERIES])
        assertEquals(DetailsSource.COMIC_INFO, d.source[ComicField.ISSUE])
        assertEquals(DetailsSource.FILE_NAME, d.source[ComicField.NOTES])
    }

    @Test
    fun `without a details file or ComicInfo, the name and the pages counted`() {
        val d = comicDetails(null, null, "00002. Action Comics #2 (July 1938).cbz", pageCount = 64)
        assertEquals("Action Comics", d.series)
        assertEquals("2", d.issue)
        assertEquals(1938, d.year)
        assertEquals(7, d.month)
        assertEquals(64, d.pageCount)
        assertEquals(DetailsSource.PAGES, d.source[ComicField.PAGE_COUNT])
        assertNull(d.writer)
    }

    @Test
    fun `year and month come from the same place`() {
        val sidecar = """{"year":1939}"""
        val d = comicDetails(sidecar, comicInfo, "x.cbz", pageCount = 1)
        assertEquals(1939, d.year)
        assertNull(d.month) // ComicInfo's June belongs to its own year
    }

    @Test
    fun `a broken details file falls back to ComicInfo`() {
        val d = comicDetails("{oops", comicInfo, "x.cbz", pageCount = 1)
        assertEquals("Action Comics", d.series)
        assertEquals(DetailsSource.COMIC_INFO, d.source[ComicField.SERIES])
    }
}
