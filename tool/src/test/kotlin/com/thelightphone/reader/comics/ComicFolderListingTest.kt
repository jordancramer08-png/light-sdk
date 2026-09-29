package com.thelightphone.reader.comics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComicFolderListingTest {

    private fun names(items: List<ComicFolderItem>) = items.map { it.name }

    @Test
    fun foldersFirstThenComicsAndNotesByFileName() {
        val children = listOf(
            "00103. Justice League #1.cbz" to false,
            "02. Book II - Earth-Two" to true,
            "00102a. Zatanna's Search (Event Notes).txt" to false,
            "10. Extras" to true,
            "00102. Detective Comics #336.cbz" to false,
            "01. Book I - Earth-One (1938-1985)" to true,
        )
        assertEquals(
            listOf(
                "01. Book I - Earth-One (1938-1985)",
                "02. Book II - Earth-Two",
                "10. Extras",
                "00102. Detective Comics #336.cbz",
                "00102a. Zatanna's Search (Event Notes).txt",
                "00103. Justice League #1.cbz",
            ),
            names(folderItems("DC Comics", children)),
        )
    }

    @Test
    fun skipsOtherFilesHiddenFilesAndMacFolders() {
        val children = listOf(
            "cover.jpg" to false,
            ".DS_Store" to false,
            "._00001. Action Comics #1.cbz" to false,
            "__MACOSX" to true,
            "old.cbr" to false,
            "00001. Action Comics #1.cbz" to false,
        )
        assertEquals(listOf("00001. Action Comics #1.cbz"), names(folderItems("", children)))
    }

    @Test
    fun kindsAndPaths() {
        val items = folderItems(
            "DC Comics",
            listOf("01. Book I" to true, "00001. Action Comics #1.cbz" to false, "Notes.TXT" to false),
        )
        assertEquals(
            listOf(
                ComicFolderItem("01. Book I", "DC Comics/01. Book I", ComicItemKind.FOLDER),
                ComicFolderItem("00001. Action Comics #1.cbz", "DC Comics/00001. Action Comics #1.cbz", ComicItemKind.COMIC),
                ComicFolderItem("Notes.TXT", "DC Comics/Notes.TXT", ComicItemKind.NOTE),
            ),
            items,
        )
        assertEquals("Action Comics #1", items[1].title)
    }

    @Test
    fun topLevelPathsHaveNoLeadingSlash() {
        assertEquals("DC Comics", folderItems("", listOf("DC Comics" to true)).single().path)
        assertEquals("", parentPath("DC Comics"))
        assertEquals("DC Comics/01. Book I", parentPath("DC Comics/01. Book I/00001. Action Comics #1.cbz"))
    }

    @Test
    fun kindOfAName() {
        assertEquals(ComicItemKind.COMIC, comicItemKind("A.CBZ", isDirectory = false))
        assertEquals(ComicItemKind.FOLDER, comicItemKind("Extras", isDirectory = true))
        assertNull(comicItemKind("A.pdf", isDirectory = false))
    }

    @Test
    fun folderSummary() {
        assertEquals("3 folders · 12 comics", folderSummaryText(3, 12))
        assertEquals("1 folder", folderSummaryText(1, 0))
        assertEquals("1 comic", folderSummaryText(0, 1))
        assertEquals("Empty", folderSummaryText(0, 0))
    }
}
