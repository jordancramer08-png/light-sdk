package com.thelightphone.reader

import com.thelightphone.reader.comics.ComicDetails
import com.thelightphone.reader.comics.ComicField
import com.thelightphone.reader.comics.ComicFolderItem
import com.thelightphone.reader.comics.ComicItemKind
import com.thelightphone.reader.comics.DetailsSource
import com.thelightphone.reader.comics.comicSlug
import com.thelightphone.reader.data.ComicMeta
import com.thelightphone.reader.data.ComicPosition
import com.thelightphone.reader.data.ComicStamp
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComicRowsTest {

    private val path = "DC Comics/00001. Action Comics #1 (1938).cbz"
    private val meta = ComicMeta(path, ComicStamp(2_500_000, 0, 1), List(30) { "$it.jpg" })

    private fun position(page: Int, at: Long = 1L, slug: String = comicSlug(path)) = ComicPosition(slug, page, 0, at)

    @Test
    fun progressText() {
        assertEquals("Not started", comicProgressText(meta, null, ReadingStatus.WANT_TO_READ))
        assertEquals("Page 12 of 30", comicProgressText(meta, position(12), ReadingStatus.READING))
        assertEquals("Finished", comicProgressText(meta, position(12), ReadingStatus.FINISHED))
        assertEquals("Page 30 of 30", comicProgressText(meta, position(99), ReadingStatus.READING))
        assertEquals("Page 3", comicProgressText(null, position(3), ReadingStatus.READING))
    }

    @Test
    fun pagesText() {
        assertEquals("30 pages", comicPagesText(meta))
        assertEquals("1 page", comicPagesText(meta.copy(pages = listOf("1.jpg"))))
        assertEquals("Preparing…", comicPagesText(null))
        assertEquals("Can't open", comicPagesText(meta.copy(pages = emptyList(), problem = true)))
    }

    @Test
    fun comicRowTitleAndState() {
        val entry = comicEntry(path, meta, mapOf(comicSlug(path) to position(4)), emptyMap())
        assertEquals("Action Comics #1 (1938)", entry.title)
        assertEquals(ReadingStatus.WANT_TO_READ, entry.status)
        assertTrue(entry.isStarted)
        assertTrue(entry.canOpen)
        assertFalse(comicEntry(path, null, emptyMap(), emptyMap()).canOpen) // not read yet
    }

    @Test
    fun folderRowsKeepTheirOrderAndKinds() {
        val items = listOf(
            ComicFolderItem("01. Book I", "DC Comics/01. Book I", ComicItemKind.FOLDER),
            ComicFolderItem("00001. Action Comics #1 (1938).cbz", path, ComicItemKind.COMIC),
            ComicFolderItem("00001a. Notes.txt", "DC Comics/00001a. Notes.txt", ComicItemKind.NOTE),
        )
        val entries = comicEntries(items, mapOf(path to meta), emptyMap(), emptyMap()) { "2 comics" }
        assertEquals(
            listOf(
                ComicEntry.Folder("DC Comics/01. Book I", "Book I", "2 comics"),
                ComicEntry.Comic(path, "Action Comics #1 (1938)", meta, null, ReadingStatus.WANT_TO_READ),
                ComicEntry.Note("DC Comics/00001a. Notes.txt", "Notes"),
            ),
            entries,
        )
    }

    @Test
    fun continueReadingIsTheLatestComicStillOnThePhone() {
        val positions = listOf(
            position(1, at = 100, slug = comicSlug("a.cbz")),
            position(1, at = 300, slug = comicSlug("gone.cbz")),
            position(1, at = 200, slug = comicSlug("b.cbz")),
        )
        assertEquals("b.cbz", lastOpenedComicPath(positions) { it != "gone.cbz" })
        assertNull(lastOpenedComicPath(emptyList()) { true })
    }

    @Test
    fun detailRows() {
        val rows = comicDetailRows(meta, null, ReadingStatus.WANT_TO_READ, Locale.US, ZoneOffset.UTC)
        assertEquals(
            listOf(
                DetailRow("Pages", "30"),
                DetailRow("Progress", "Not started"),
                DetailRow("Folder", "DC Comics"),
                DetailRow("File", "00001. Action Comics #1 (1938).cbz"),
                DetailRow("File size", "2.4 MB"),
                DetailRow("Added", "Jan 1, 1970"),
                DetailRow("Last read", "Not yet"),
            ),
            rows,
        )
    }

    @Test
    fun sectionFallsBackToBooks() {
        assertEquals(LibrarySection.COMICS, LibrarySection.fromSavedName("COMICS"))
        assertEquals(LibrarySection.BOOKS, LibrarySection.fromSavedName(null))
        assertEquals(LibrarySection.BOOKS, LibrarySection.fromSavedName("MAGAZINES"))
    }

    // --- Comic Details' top part ---------------------------------------------------------

    @Test
    fun comicHeadingIsSeriesVolumeAndIssue() {
        assertEquals("Action Comics #1", comicHeading(ComicDetails(series = "Action Comics", issue = "1"), "x"))
        assertEquals("Batman Vol. 2 #1", comicHeading(ComicDetails(series = "Batman", issue = "1", volume = "2"), "x"))
        assertEquals("Watchmen", comicHeading(ComicDetails(title = "Watchmen"), "x"))
        assertEquals("From the name", comicHeading(ComicDetails(), "From the name"))
        assertEquals("The Legend", comicStoryTitle(ComicDetails(series = "Batman", title = "The Legend")))
        assertNull(comicStoryTitle(ComicDetails(title = "Watchmen"))) // already the heading
    }

    @Test
    fun comicDateTextShowsTheMonthWhenKnown() {
        assertEquals("June 1938", comicDateText(ComicDetails(year = 1938, month = 6), Locale.US))
        assertEquals("1938", comicDateText(ComicDetails(year = 1938), Locale.US))
        assertNull(comicDateText(ComicDetails(), Locale.US))
    }

    @Test
    fun creditRowsLeaveOutWhatIsntKnown() {
        assertEquals(
            listOf(DetailRow("Writer", "Jerry Siegel"), DetailRow("Artist", "Joe Shuster")),
            comicCreditRows(ComicDetails(writer = "Jerry Siegel", artists = listOf("Joe Shuster"))),
        )
        assertEquals(
            listOf(DetailRow("Artists", "A, B"), DetailRow("Publisher", "DC")),
            comicCreditRows(ComicDetails(artists = listOf("A", "B"), publisher = "DC")),
        )
        assertEquals(emptyList(), comicCreditRows(ComicDetails()))
    }

    @Test
    fun sourceTextNamesWhereTheDetailsCameFromBestFirst() {
        val details = ComicDetails(
            source = mapOf(
                ComicField.SERIES to DetailsSource.FILE_NAME,
                ComicField.WRITER to DetailsSource.COMIC_INFO,
                ComicField.PAGE_COUNT to DetailsSource.PAGES,
            ),
        )
        assertEquals("From ComicInfo.xml and the file name.", comicDetailsSourceText(details))
        assertEquals("", comicDetailsSourceText(ComicDetails()))
    }

    // --- Remove from phone ---------------------------------------------------------------

    @Test
    fun removalQuestionsNameWhatGoesAndTheSpace() {
        val gb = 1024L * 1024 * 1024
        assertEquals("Remove 12 comics (1.4 GB)?", folderRemovalQuestion(RemovalSummary(comics = 12, bytes = gb * 14 / 10)))
        assertEquals("Remove 1 comic and 2 notes (24 MB)?", folderRemovalQuestion(RemovalSummary(1, 2, 24L * 1024 * 1024)))
        assertEquals("Remove 1 note (1 KB)?", folderRemovalQuestion(RemovalSummary(notes = 1, bytes = 10)))
        assertEquals("Remove this empty folder?", folderRemovalQuestion(RemovalSummary()))
        assertEquals("Remove “Action Comics #1” (45 MB)?", itemRemovalQuestion("Action Comics #1", 45L * 1024 * 1024))
    }
}
