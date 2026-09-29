package com.thelightphone.reader.comics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComicNamesTest {

    // --- cleaning file names ---------------------------------------------------------

    @Test
    fun dropsTheReadingOrderNumberAndExtension() {
        assertEquals("Action Comics #1 (1938)", cleanComicTitle("00001. Action Comics #1 (1938).cbz"))
    }

    @Test
    fun dropsANumberWithALetterFromANote() {
        assertEquals("Zatanna's Search (Event Notes)", cleanComicTitle("00102a. Zatanna's Search (Event Notes).txt"))
    }

    @Test
    fun cleansFolderNamesButKeepsTheirDots() {
        assertEquals("Book I - Earth-One (1938-1985)", cleanComicTitle("01. Book I - Earth-One (1938-1985)"))
        assertEquals("Sandman Vol. 2", cleanComicTitle("03. Sandman Vol. 2"))
    }

    @Test
    fun otherSeparatorsAfterTheNumber() {
        assertEquals("Watchmen", cleanComicTitle("01 - Watchmen.cbz"))
        assertEquals("Watchmen", cleanComicTitle("7) Watchmen.cbz"))
        assertEquals("Watchmen", cleanComicTitle("12_Watchmen.cbz"))
    }

    @Test
    fun aNumberThatIsPartOfTheTitleStays() {
        assertEquals("2000 AD Prog 1", cleanComicTitle("2000 AD Prog 1.cbz"))
        assertEquals("100 Bullets 001", cleanComicTitle("100 Bullets 001.cbz"))
        assertEquals("Saga 001", cleanComicTitle("Saga 001.cbz"))
    }

    @Test
    fun aNameThatIsOnlyANumberKeepsIt() {
        assertEquals("1984", cleanComicTitle("1984.cbz"))
        assertEquals("00001.", cleanComicTitle("00001..cbz"))
    }

    @Test
    fun extensionCaseDoesNotMatter() {
        assertEquals("Hellboy", cleanComicTitle("004. Hellboy.CBZ"))
    }

    // --- natural order -------------------------------------------------------------------

    @Test
    fun numbersSortByValue() {
        val names = listOf("Page 10.jpg", "Page 2.jpg", "Page 1.jpg")
        assertEquals(listOf("Page 1.jpg", "Page 2.jpg", "Page 10.jpg"), names.sortedWith(NaturalOrder))
    }

    @Test
    fun aNoteWithALetterComesAfterItsComic() {
        val names = listOf(
            "00103. Justice League #1.cbz",
            "00102a. Zatanna's Search (Event Notes).txt",
            "00102. Detective Comics #336.cbz",
        )
        assertEquals(
            listOf(
                "00102. Detective Comics #336.cbz",
                "00102a. Zatanna's Search (Event Notes).txt",
                "00103. Justice League #1.cbz",
            ),
            names.sortedWith(NaturalOrder),
        )
    }

    @Test
    fun lettersIgnoreCaseAndPaddingIsTheSameNumber() {
        assertTrue(naturalCompare("batman", "Superman") < 0)
        assertTrue(naturalCompare("007", "7") != 0) // equal by value, but still a fixed order
        assertEquals(listOf("2", "007", "10"), listOf("10", "007", "2").sortedWith(NaturalOrder))
    }

    // --- keys ------------------------------------------------------------------------------

    @Test
    fun comicKeysRoundTripAndNeverLookLikeBooks() {
        val path = "DC Comics/01. Book I/00001. Action Comics #1 (1938).cbz"
        assertEquals(path, comicPathOf(comicSlug(path)))
        assertNull(comicPathOf("the-shadow-of-the-wind"))
    }
}
