package com.thelightphone.reader.data

import com.thelightphone.reader.comics.ComicItemKind
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

    private val store = ComicStore(comicsDir, cacheDir, ::fakeSaveCover)

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
}
