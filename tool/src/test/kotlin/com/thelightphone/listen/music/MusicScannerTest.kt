package com.thelightphone.listen.music

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MusicScannerTest {

    private val root: File = Files.createTempDirectory("listen-scan").toFile()
    private val music = File(root, "Music")
    private val lastSync = File(root, ".state/last-sync.txt")

    /** Files whose tags were read, in order. The "tags" are just the file's text. */
    private val read = mutableListOf<String>()
    private val scanner = MusicScanner(music, lastSync) { file ->
        read += file.relativeTo(music).invariantSeparatorsPath
        RawTags(title = file.readText())
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun put(path: String, text: String = path, modified: Long = 1_000_000L): File =
        File(music, path).apply {
            parentFile.mkdirs()
            writeText(text)
            setLastModified(modified)
        }

    @Test
    fun `finds audio files and skips others and hidden ones`() {
        put("A/Album/01.mp3")
        put("A/Album/02.FLAC")
        put("A/Album/cover.jpg")
        put("A/Album/.hidden.mp3")
        put(".trash/B/x.mp3")
        put("C/Book/ch1.m4b")
        val found = scanner.findFiles().map { it.path }.sorted()
        assertEquals(listOf("A/Album/01.mp3", "A/Album/02.FLAC", "C/Book/ch1.m4b"), found)
    }

    @Test
    fun `second scan reads only new and changed files`() {
        put("A/Album/01.mp3", "one")
        put("A/Album/02.mp3", "two")
        put("A/Album/03.mp3", "three")
        val first = scanner.scan(emptyList())
        assertEquals(3, read.size)

        read.clear()
        put("A/Album/02.mp3", "two, retagged", modified = 2_000_000L) // changed
        File(music, "A/Album/03.mp3").delete() // removed
        put("B/New/01.mp3", "new") // added
        val second = scanner.scan(first)

        assertEquals(listOf("A/Album/02.mp3", "B/New/01.mp3"), read.sorted())
        assertEquals(
            listOf("A/Album/01.mp3" to "one", "A/Album/02.mp3" to "two, retagged", "B/New/01.mp3" to "new"),
            second.map { it.path to it.title }.sortedBy { it.first },
        )
    }

    @Test
    fun `stamp changes when the PC script syncs or a folder changes`() {
        put("A/Album/01.mp3")
        val before = scanner.stamp()
        assertEquals(before, scanner.stamp())

        lastSync.parentFile.mkdirs()
        lastSync.writeText("2026-09-29T16:36:18")
        val afterSync = scanner.stamp()
        assertNotEquals(before, afterSync)

        File(music, "B/New").mkdirs()
        assertNotEquals(afterSync, scanner.stamp())
    }

    @Test
    fun `no music folder means no songs`() {
        music.deleteRecursively()
        assertTrue(scanner.findFiles().isEmpty())
        assertTrue(scanner.stamp().endsWith("no-music"))
    }

    @Test
    fun `plan keeps songs whose size and time match`() {
        val old = Song("A/B/1.mp3", 10, 5, "t", "a", "a", "b")
        val plan = planRescan(
            listOf(old, Song("A/B/gone.mp3", 1, 1, "g", "a", "a", "b")),
            listOf(FoundFile("A/B/1.mp3", 10, 5), FoundFile("A/B/2.mp3", 3, 3)),
        )
        assertEquals(listOf(old), plan.kept)
        assertEquals(listOf(FoundFile("A/B/2.mp3", 3, 3)), plan.toRead)

        val resized = planRescan(listOf(old), listOf(FoundFile("A/B/1.mp3", 11, 5)))
        assertTrue(resized.kept.isEmpty())
    }
}
