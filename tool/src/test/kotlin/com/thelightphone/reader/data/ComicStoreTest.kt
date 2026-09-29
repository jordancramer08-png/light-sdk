package com.thelightphone.reader.data

import com.thelightphone.reader.comics.ComicItemKind
import com.thelightphone.reader.comics.PageLevels
import com.thelightphone.reader.comics.PixelRect
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComicStoreTest {

    private val root: File = Files.createTempDirectory("comicstore").toFile()
    private val comicsDir = File(root, "shared/comics").apply { mkdirs() }
    private val cacheDir = File(root, "comic-library")

    /** Stands in for CoverImages (Android's image classes don't run on the PC): writes the bytes as the cover. */
    private val savedCovers = mutableListOf<ByteArray>()

    private fun fakeSaveCover(bytes: ByteArray, folder: File): Boolean {
        savedCovers.add(bytes)
        File(folder, CoverSize.SMALL.fileName).writeBytes(bytes)
        return true
    }

    /** Stands in for the detector: page bytes [n] give n panels on a 100 × 200 page; 0 can't be read. */
    private val pagesLookedAt = mutableListOf<Int>()

    private fun fakeFindPanels(bytes: ByteArray): PagePanels? {
        val n = bytes.first().toInt()
        pagesLookedAt.add(n)
        if (n == 0) return null
        val levels = LevelsBox(black = listOf(10, 12, 14), white = listOf(240, 230, 180))
        return PagePanels(100, 200, (0 until n).map { PanelBox(0, it * 10, 100, it * 10 + 10) }, PanelBox(5, 6, 95, 194), levels)
    }

    private val store = ComicStore(comicsDir, cacheDir, ::fakeSaveCover, ::fakeFindPanels)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** Puts a CBZ with [pages] (name -> bytes) at [path] in the comics folder. */
    private fun addComic(path: String, vararg pages: Pair<String, ByteArray>) {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, data) in pages) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        File(comicsDir, path).apply { parentFile.mkdirs() }.writeBytes(bytes.toByteArray())
    }

    @Test
    fun listsAFolderInReadingOrder() {
        addComic("DC Comics/01. Book I/00002. Detective Comics #27 (1939).cbz", "1.jpg" to byteArrayOf(1))
        addComic("DC Comics/01. Book I/00001. Action Comics #1 (1938).cbz", "1.jpg" to byteArrayOf(1))
        File(comicsDir, "DC Comics/01. Book I/00001a. Notes.txt").writeText("A note")
        File(comicsDir, "DC Comics/01. Book I/10. Later").mkdirs()
        File(comicsDir, "DC Comics/01. Book I/2. Sooner").mkdirs()

        val items = store.list("DC Comics/01. Book I")
        assertEquals(
            listOf("2. Sooner", "10. Later", "00001. Action Comics #1 (1938).cbz", "00001a. Notes.txt", "00002. Detective Comics #27 (1939).cbz"),
            items.map { it.name },
        )
        assertEquals(
            listOf(ComicItemKind.FOLDER, ComicItemKind.FOLDER, ComicItemKind.COMIC, ComicItemKind.NOTE, ComicItemKind.COMIC),
            items.map { it.kind },
        )
        assertEquals("DC Comics/01. Book I/2. Sooner", items.first().path)
        assertEquals(listOf("DC Comics"), store.list("").map { it.path })
        assertEquals(1 to 0, store.folderCounts("DC Comics"))
        assertEquals(2 to 2, store.folderCounts("DC Comics/01. Book I"))
    }

    @Test
    fun readsPagesAndCoverOnceThenUsesTheCache() {
        val path = "Marvel/001. Fantastic Four #1.cbz"
        addComic(path, "p10.jpg" to byteArrayOf(10), "p2.jpg" to byteArrayOf(2), "ComicInfo.xml" to byteArrayOf(0))
        assertNull(store.cached(path))

        val meta = assertNotNull(store.prepared(path))
        assertEquals(listOf("p2.jpg", "p10.jpg"), meta.pages)
        assertFalse(meta.problem)
        assertContentEquals(byteArrayOf(2), store.coverFile(meta).readBytes())

        assertEquals(meta, store.cached(path))
        assertEquals(meta, store.prepared(path))
        assertEquals(1, savedCovers.size) // not read again
    }

    @Test
    fun aChangedFileIsReadAgain() {
        val path = "a.cbz"
        addComic(path, "1.jpg" to byteArrayOf(1))
        store.prepared(path)
        addComic(path, "1.jpg" to byteArrayOf(1), "2.jpg" to byteArrayOf(2))
        File(comicsDir, path).setLastModified(File(comicsDir, path).lastModified() + 5_000)

        assertNull(store.cached(path))
        assertEquals(2, store.prepared(path)?.pageCount)
    }

    @Test
    fun aBrokenFileIsCachedAsAProblem() {
        File(comicsDir, "broken.cbz").writeText("not a zip")
        val meta = assertNotNull(store.prepared("broken.cbz"))
        assertTrue(meta.problem)
        assertFalse(store.coverFile(meta).exists())
        assertNull(store.prepared("gone.cbz"))
    }

    @Test
    fun removesTheCacheOfComicsTakenOffThePhone() {
        addComic("keep.cbz", "1.jpg" to byteArrayOf(1))
        addComic("remove.cbz", "1.jpg" to byteArrayOf(1))
        store.prepared("keep.cbz")
        store.prepared("remove.cbz")
        File(comicsDir, "remove.cbz").delete()
        File(cacheDir, "half-written").mkdirs()

        store.removeOrphans()
        assertEquals(setOf(ComicStore.cacheId("keep.cbz")), cacheDir.list().orEmpty().toSet())
    }

    @Test
    fun readsNotes() {
        File(comicsDir, "n.txt").writeText("﻿Line one\r\nLine two")
        assertEquals("Line one\nLine two", store.noteText("n.txt"))
    }

    @Test
    fun looksForEachPagesPanelsOnceThenUsesTheCache() {
        addComic("a.cbz", "1.jpg" to byteArrayOf(2), "2.jpg" to byteArrayOf(3))
        val meta = assertNotNull(store.prepared("a.cbz"))
        assertNull(store.cachedPanels(meta, "1.jpg"))

        assertEquals(2, store.panels(meta, "1.jpg")?.panels?.size)
        assertEquals(3, store.panels(meta, "2.jpg")?.panels?.size)
        assertEquals(2, store.panels(meta, "1.jpg")?.panels?.size)
        assertEquals(listOf(2, 3), pagesLookedAt) // each page looked at once

        // Another screen's store reads the same panels.json.
        val again = ComicStore(comicsDir, cacheDir, ::fakeSaveCover, ::fakeFindPanels)
        assertEquals(PanelBox(0, 10, 100, 20), again.cachedPanels(meta, "1.jpg")?.panels?.get(1))
        assertEquals(listOf(2, 3), pagesLookedAt)
    }

    @Test
    fun aPagesCropAndLevelsAreCachedWithItsPanels() {
        addComic("a.cbz", "1.jpg" to byteArrayOf(2))
        val meta = assertNotNull(store.prepared("a.cbz"))
        store.panels(meta, "1.jpg")
        val cached = ComicStore(comicsDir, cacheDir, ::fakeSaveCover, ::fakeFindPanels).cachedPanels(meta, "1.jpg")
        assertEquals(PixelRect(5, 6, 95, 194), cached?.crop?.rect)
        assertEquals(PageLevels(10, 12, 14, 240, 230, 180), cached?.levels?.levels)
    }

    @Test
    fun anUnreadablePageIsNotCached() {
        addComic("a.cbz", "1.jpg" to byteArrayOf(0))
        val meta = assertNotNull(store.prepared("a.cbz"))
        assertNull(store.panels(meta, "1.jpg"))
        assertNull(store.panels(meta, "missing.jpg"))
        assertNull(store.cachedPanels(meta, "1.jpg"))
    }

    @Test
    fun panelsFromAnOlderDetectorAreLookedForAgain() {
        addComic("a.cbz", "1.jpg" to byteArrayOf(2))
        val meta = assertNotNull(store.prepared("a.cbz"))
        File(cacheDir, "${ComicStore.cacheId("a.cbz")}/panels.json")
            .writeText("""{"version":0,"pages":{"1.jpg":{"pageWidth":1,"pageHeight":1,"panels":[]}}}""")
        assertNull(store.cachedPanels(meta, "1.jpg"))
        assertEquals(2, store.panels(meta, "1.jpg")?.panels?.size)
    }

    @Test
    fun aChangedComicDropsItsPanels() {
        val path = "a.cbz"
        addComic(path, "1.jpg" to byteArrayOf(2))
        val old = assertNotNull(store.prepared(path))
        store.panels(old, "1.jpg")
        addComic(path, "1.jpg" to byteArrayOf(4))
        File(comicsDir, path).setLastModified(File(comicsDir, path).lastModified() + 5_000)

        val new = assertNotNull(store.prepared(path))
        assertNull(store.cachedPanels(new, "1.jpg"))
        assertEquals(4, store.panels(new, "1.jpg")?.panels?.size)
        // Asking with the old details neither reads nor writes the new comic's panels.
        assertNull(store.cachedPanels(old, "1.jpg"))
        store.panels(old, "1.jpg")
        assertEquals(4, store.cachedPanels(new, "1.jpg")?.panels?.size)
    }
}
