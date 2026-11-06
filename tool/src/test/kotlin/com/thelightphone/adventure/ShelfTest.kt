package com.thelightphone.adventure

import com.thelightphone.adventure.engine.Story
import com.thelightphone.adventure.engine.ZMachineError
import com.thelightphone.adventure.engine.storyFromBlorb
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers

class ShelfTest {
    private val root: File = Files.createTempDirectory("adventure-shelf").toFile()
    private val games = File(root, "games").also { it.mkdirs() }
    private val data = File(root, "data")
    private val assets = File("src/main/assets")
    private var now = 1_000L

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun shelf(access: Boolean = true) = ShelfViewModel(
        readAsset = { File(assets, it).readBytes() },
        gamesFolder = games,
        dataFolder = data,
        hasStorageAccess = { access },
        clock = { now },
        dispatcher = Dispatchers.Unconfined, // Runs at once, so tests need no waiting.
    )

    /** A different game to Zork: a copy with another serial number. */
    private fun addGame(name: String, serial: String = "999999"): File {
        val bytes = File(assets, "zork3.dat").readBytes()
        serial.forEachIndexed { index, char -> bytes[0x12 + index] = char.code.toByte() }
        return File(games, name).also { it.writeBytes(bytes) }
    }

    private fun ShelfViewModel.titles() = state.value.entries.map { it.title }

    private fun ShelfViewModel.entry(title: String) = state.value.entries.first { it.title == title }

    @Test
    fun namesAreCleanedUp() {
        assertEquals("my great game", cleanName("my_great_game.z5"))
        assertEquals("Lost Pig", cleanName("Lost_Pig.z8"))
        assertEquals("a.b", cleanName("a.b.zblorb"))
        assertEquals(".z5", cleanName(".z5"))
    }

    @Test
    fun onlyStoryFilesCount() {
        listOf("a.z3", "a.z4", "a.Z5", "a.z8", "a.zblorb", "a.zlb").forEach { assertTrue(isStoryFile(File(it)), it) }
        listOf("a.txt", "a.z6", "a").forEach { assertFalse(isStoryFile(File(it)), it) }
    }

    @Test
    fun bundledGamesAreAlwaysThere() {
        val shelf = shelf(access = false)
        assertEquals(listOf("Zork I", "Zork II", "Zork III"), shelf.titles())
        assertTrue(shelf.state.value.needsStorageAccess)
        assertEquals(3, shelf.entry("Zork I").version)
    }

    @Test
    fun addedGamesJoinInNameOrder() {
        addGame("my_game.z5")
        File(games, "notes.txt").writeText("not a game")
        assertEquals(listOf("my game", "Zork I", "Zork II", "Zork III"), shelf().titles())
    }

    @Test
    fun addedGamesAreLeftOutWithoutStorageAccess() {
        addGame("my_game.z5")
        assertEquals(3, shelf(access = false).titles().size)
    }

    @Test
    fun aDamagedFileIsListedAsUnreadableNotFatal() {
        File(games, "broken.z5").writeBytes(ByteArray(10))
        File(games, "version6.z5").writeBytes(ByteArray(100).also { it[0] = 6 })
        val state = shelf().state.value
        assertEquals(3, state.entries.size)
        assertEquals(listOf("broken.z5", "version6.z5"), state.unreadable.sorted())
    }

    @Test
    fun aCopyOfAGameIsShownOnce() {
        File(assets, "zork1.dat").copyTo(File(games, "zork_copy.z3"))
        assertEquals(3, shelf().titles().size)
    }

    @Test
    fun playingSetsTheDateAndContinue() {
        val shelf = shelf()
        assertNull(shelf.state.value.continueGame)
        assertTrue("not played yet" in shelfDetail(shelf.entry("Zork II")))
        now = 5_000
        shelf.markPlayed(shelf.entry("Zork II"))
        shelf.refresh()
        assertEquals("Zork II", shelf.state.value.continueGame?.title)
        assertEquals(5_000, shelf.entry("Zork II").lastPlayed)
    }

    @Test
    fun continueOpensTheMostRecentGameAcrossRestarts() {
        val first = shelf()
        first.markPlayed(first.entry("Zork II"))
        first.markPlayed(first.entry("Zork I"))
        assertEquals("Zork I", shelf().state.value.continueGame?.title)
    }

    @Test
    fun renamingKeepsTheDateAndCanBeUndone() {
        val shelf = shelf()
        shelf.markPlayed(shelf.entry("Zork I"))
        shelf.startRename(shelf.entry("Zork I"))
        shelf.finishRename("  The Great Empire ")
        assertNull(shelf.state.value.renaming)
        val renamed = shelf.entry("The Great Empire")
        assertNotNull(renamed.lastPlayed)
        assertEquals("The Great Empire", shelf().state.value.continueGame?.title)
        shelf.startRename(renamed)
        shelf.finishRename("")
        assertTrue("Zork I" in shelf.titles())
    }

    @Test
    fun bundledGamesCannotBeRemoved() {
        val shelf = shelf()
        shelf.askToRemove(shelf.entry("Zork I"))
        assertNull(shelf.state.value.removing)
    }

    @Test
    fun removeNeedsConfirmation() {
        val file = addGame("my_game.z5")
        val shelf = shelf()
        shelf.askToRemove(shelf.entry("my game"))
        assertTrue(file.exists())
        shelf.cancelRemove()
        assertNull(shelf.state.value.removing)
        assertTrue(file.exists())
    }

    @Test
    fun removingDeletesTheGameItsSavesAndNothingElse() {
        val file = addGame("my_game.z5")
        val other = addGame("other.z5", "888888")
        val shelf = shelf()
        val entry = shelf.entry("my game")
        val keep = shelf.entry("other")
        SaveStore(data, entry.key).write(1, byteArrayOf(1), "Here")
        SaveStore(data, keep.key).write(1, byteArrayOf(2), "There")
        shelf.markPlayed(entry)

        shelf.askToRemove(entry)
        shelf.confirmRemove()

        assertFalse(file.exists())
        assertTrue(other.exists())
        assertNull(SaveStore(data, entry.key).read(1))
        assertNotNull(SaveStore(data, keep.key).read(1))
        assertFalse("my game" in shelf.titles())
        assertNull(shelf.state.value.continueGame)
    }

    @Test
    fun aFileOutsideTheGamesFolderIsNeverDeleted() {
        val outside = File(root, "outside.z5").also { addGame("x.z5").copyTo(it) }
        val shelf = shelf()
        val entry = shelf.entry("x").copy(source = GameSource.Added(outside))
        SaveStore(data, entry.key).write(1, byteArrayOf(1), "Here")
        shelf.askToRemove(entry)
        shelf.confirmRemove()
        assertTrue(outside.exists())
        assertNotNull(SaveStore(data, entry.key).read(1))
        assertEquals("Could not remove x", shelf.state.value.message)
    }

    @Test
    fun aGameThatHasGoneGivesEmptyBytes() {
        val file = addGame("my_game.z5")
        val shelf = shelf()
        val entry = shelf.entry("my game")
        file.delete()
        assertEquals(0, shelf.storyBytes(entry).size)
    }

    @Test
    fun blorbGivesItsStory() {
        val story = byteArrayOf(5, 6, 7)
        val body = "IFRS".toByteArray() +
            "JPEG".toByteArray() + byteArrayOf(0, 0, 0, 1, 9, 0) +
            "ZCOD".toByteArray() + byteArrayOf(0, 0, 0, 3) + story
        val file = "FORM".toByteArray() + byteArrayOf(0, 0, 0, body.size.toByte()) + body
        assertContentEquals(story, storyFromBlorb(file))
        assertContentEquals(story, storyFromBlorb(story))
    }

    @Test
    fun aBlorbWithNoStoryIsRefused() {
        val file = "FORM".toByteArray() + byteArrayOf(0, 0, 0, 4) + "IFRS".toByteArray()
        assertFailsWith<ZMachineError> { storyFromBlorb(file) }
    }

    @Test
    fun realBlorbGamesOpen() {
        val file = File("../story-files/Galatea.zblorb")
        if (!file.exists()) return
        assertEquals(8, Story(storyFromBlorb(file.readBytes())).header.version)
    }
}
