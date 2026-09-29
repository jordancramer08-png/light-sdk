package com.thelightphone.reader.comics

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComicPagesTest {

    private val dir: File = Files.createTempDirectory("comicpages").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** Writes a CBZ holding [entries] (name -> bytes); a name ending in "/" is a folder. */
    private fun cbz(vararg entries: Pair<String, ByteArray>): File {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return File(dir, "comic.cbz").apply { writeBytes(bytes.toByteArray()) }
    }

    @Test
    fun pagesSortNaturally() {
        val pages = comicPages(listOf("page10.jpg", "page2.jpg", "page1.jpg", "page11.png"))
        assertEquals(listOf("page1.jpg", "page2.jpg", "page10.jpg", "page11.png"), pages)
    }

    @Test
    fun skipsNonImagesFoldersAndMacLeftovers() {
        val pages = comicPages(
            listOf(
                "ComicInfo.xml",
                "readme.txt",
                "Scans/",
                "__MACOSX/Scans/._001.jpg",
                "Scans/._001.jpg",
                "Scans/.DS_Store",
                "Scans/Thumbs.db",
                "Scans/002.JPEG",
                "Scans/001.jpg",
            ),
        )
        assertEquals(listOf("Scans/001.jpg", "Scans/002.JPEG"), pages)
    }

    @Test
    fun everyPictureTypeCounts() {
        for (extension in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")) {
            assertTrue(isPageImage("001.$extension"), extension)
        }
        assertFalse(isPageImage("001.pdf"))
        assertFalse(isPageImage("jpg"))
    }

    @Test
    fun chaptersInFoldersStayInOrder() {
        val pages = comicPages(listOf("Chapter 10/01.jpg", "Chapter 2/02.jpg", "Chapter 2/01.jpg"))
        assertEquals(listOf("Chapter 2/01.jpg", "Chapter 2/02.jpg", "Chapter 10/01.jpg"), pages)
    }

    @Test
    fun readsThePagesOfARealZip() {
        val file = cbz(
            "Scans/" to ByteArray(0),
            "Scans/10.jpg" to byteArrayOf(10),
            "Scans/9.jpg" to byteArrayOf(9),
            "__MACOSX/Scans/._9.jpg" to byteArrayOf(0),
            "ComicInfo.xml" to "<ComicInfo/>".toByteArray(),
        )
        assertEquals(listOf("Scans/9.jpg", "Scans/10.jpg"), readComicPages(file))
        assertContentEquals(byteArrayOf(9), readComicEntry(file, "Scans/9.jpg"))
        assertNull(readComicEntry(file, "Scans/missing.jpg"))
        assertNull(readComicEntry(file, "Scans/10.jpg", maxBytes = 0))
    }

    @Test
    fun aFileThatIsNotAZipFails() {
        val file = File(dir, "broken.cbz").apply { writeText("not a zip") }
        assertFailsWith<Exception> { readComicPages(file) }
    }

    @Test
    fun noteTextIsUtf8OrWindows1252() {
        assertEquals("Café\nnext", decodeNoteText("﻿Café\r\nnext".toByteArray(Charsets.UTF_8)))
        assertEquals("Café", decodeNoteText("Café".toByteArray(charset("windows-1252"))))
    }
}
