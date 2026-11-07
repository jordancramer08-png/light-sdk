package com.thelightphone.adventure

import com.thelightphone.adventure.engine.Story
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SaveMenuTest {
    private val folder: File = Files.createTempDirectory("adventure-saves").toFile()
    private val running = ArrayList<GameViewModel>()
    private val zork1 = File("src/main/assets/zork1.dat").readBytes()
    private val zork2 = File("src/main/assets/zork2.dat").readBytes()

    @AfterTest
    fun cleanUp() {
        running.forEach(::leave)
        folder.deleteRecursively()
    }

    private fun start(bytes: ByteArray = zork1) =
        GameViewModel(bytes, folder, seed = 42).also { running += it }

    /** What closing the screen does, then waits for the automatic save to finish. */
    private fun leave(game: GameViewModel) {
        game.stop()
        runBlocking { withTimeout(20_000) { game.awaitEnd() } }
    }

    private fun await(game: GameViewModel, ready: (GameUiState) -> Boolean) = runBlocking {
        withTimeout(20_000) { game.state.first(ready) }
    }

    private fun prompt(game: GameViewModel) = await(game) { it.waitingForInput }

    private fun play(game: GameViewModel, vararg commands: String): GameUiState {
        var state = prompt(game)
        commands.forEach { command ->
            val next = state.commandsTaken + 1
            game.send(command)
            state = await(game) { it.waitingForInput && it.commandsTaken == next }
        }
        return state
    }

    private fun awaitPicker(game: GameViewModel) = runBlocking {
        withTimeout(20_000) { game.picker.first { it != null } }!!
    }

    /** Waits until the engine has shown [message], so the action has really happened. */
    private fun awaitMessage(game: GameViewModel, message: String) =
        await(game) { it.waitingForInput && message in it.text() }

    private fun GameUiState.text() = lines.joinToString("\n") { line -> line.joinToString("") { it.text } }

    private fun menuSave(game: GameViewModel, slot: Int) {
        game.openPicker(SlotMode.SAVE)
        game.pickSlot(slot)
        awaitMessage(game, "[Saved in slot $slot.]")
    }

    private fun menuRestore(game: GameViewModel, slot: Int) {
        game.openPicker(SlotMode.RESTORE)
        game.pickSlot(slot)
        awaitMessage(game, "[Restored slot $slot.]")
    }

    @Test
    fun saveThenRestoreGivesTheSameState() {
        val game = start()
        play(game, "open mailbox", "take leaflet", "north")
        menuSave(game, 1)
        play(game, "drop leaflet", "east")
        menuRestore(game, 1)
        val before = prompt(game).status
        assertEquals("North of House", before?.location)
        assertEquals(3, before?.second)
        val state = play(game, "inventory")
        assertTrue("A leaflet" in state.text(), state.text())
        assertEquals(4, state.status?.second)
    }

    @Test
    fun aSlotLoadsInAFreshGame() {
        val first = start()
        play(first, "open mailbox", "take leaflet")
        menuSave(first, 2)
        leave(first)

        val second = start()
        prompt(second)
        menuRestore(second, 2)
        assertTrue("A leaflet" in play(second, "inventory").text())
    }

    @Test
    fun resumesAfterLeaving() {
        val first = start()
        val left = play(first, "open mailbox", "take leaflet", "north")
        leave(first)

        val second = start()
        val state = prompt(second)
        assertEquals("North of House", state.status?.location)
        assertEquals(left.status, state.status)
        assertEquals(0, state.commandsTaken)
        assertTrue("North of House" in state.text(), state.text())
        assertTrue("A leaflet" in play(second, "inventory").text())
    }

    @Test
    fun pausingTheAppSavesWithoutEndingTheGame() {
        val game = start()
        play(game, "north")
        game.autosave()
        assertEquals("North of House", play(game, "look").status?.location)
    }

    @Test
    fun quittingLeavesNothingToResume() {
        val first = start()
        play(first, "north", "quit")
        first.send("y")
        await(first) { it.finished }
        leave(first)
        val state = prompt(start())
        assertEquals("West of House", state.status?.location)
    }

    @Test
    fun twoGamesKeepSeparateSaves() {
        val one = start(zork1)
        play(one, "north")
        menuSave(one, 1)
        leave(one)

        val two = start(zork2)
        val state = prompt(two)
        assertFalse("North of House" in state.text(), state.text())
        two.openPicker(SlotMode.RESTORE)
        assertNull(assertNotNull(two.picker.value).slots.first().savedAt)
        two.cancelPicker()
        leave(two)

        assertEquals(2, File(folder, "saves").listFiles()?.size)
        assertNotEquals(gameKey(Story(zork1)), gameKey(Story(zork2)))
        assertEquals("North of House", prompt(start(zork1)).status?.location)
    }

    private fun asset(name: String) = File("src/main/assets/$name").readBytes()

    /** Each original game beside its Bestiary edition. */
    private fun editions() = (1..3).map { asset("zork$it.dat") to asset("zork$it-bestiary.dat") }

    private fun slotIsEmpty(game: GameViewModel, slot: Int): Boolean {
        prompt(game)
        game.openPicker(SlotMode.RESTORE)
        val empty = assertNotNull(game.picker.value).slots[slot - 1].savedAt == null
        game.cancelPicker()
        return empty
    }

    @Test
    fun aBestiaryGameCannotSeeItsOriginalsSaves() {
        editions().forEach { (original, bestiary) ->
            val first = start(original)
            prompt(first)
            menuSave(first, 1)
            leave(first)

            val second = start(bestiary)
            assertTrue(slotIsEmpty(second, 1))
            menuSave(second, 2)
            leave(second)

            val again = start(original)
            assertTrue(slotIsEmpty(again, 2))
            leave(again)
        }
        assertEquals(6, File(folder, "saves").listFiles()?.size)
    }

    @Test
    fun zorkIAndItsBestiaryEachRestoreTheirOwnPlace() {
        val bestiary = asset("zork1-bestiary.dat")
        val original = start(zork1)
        play(original, "north")
        menuSave(original, 1)
        leave(original)
        val mod = start(bestiary)
        play(mod, "south")
        menuSave(mod, 1)
        play(mod, "east")
        menuRestore(mod, 1)
        assertEquals("South of House", prompt(mod).status?.location)
        leave(mod)

        val back = start(zork1)
        play(back, "east")
        menuRestore(back, 1)
        assertEquals("North of House", prompt(back).status?.location)
    }

    /** Zork's banner keeps only 11 bits of the release, so a number over 2047 would print wrong. */
    @Test
    fun bestiaryBannersShowTheirHeaderRelease() {
        (1..3).forEach { number ->
            val state = prompt(start(asset("zork$number-bestiary.dat")))
            assertTrue("Release 100$number /" in state.text(), state.text())
        }
    }

    @Test
    fun aBestiarySaveMovedToTheOriginalIsRefused() {
        editions().forEach { (original, bestiary) ->
            val mod = start(bestiary)
            prompt(mod)
            menuSave(mod, 1)
            leave(mod)
            val from = File(folder, "saves/${gameKey(Story(bestiary))}/slot1.qzl")
            from.copyTo(File(folder, "saves/${gameKey(Story(original))}/slot1.qzl"))

            val game = start(original)
            prompt(game)
            game.openPicker(SlotMode.RESTORE)
            game.pickSlot(1)
            awaitMessage(game, "[Could not restore slot 1.]")
            leave(game)
        }
    }

    @Test
    fun gameKeyUsesReleaseSerialAndChecksum() {
        val key = gameKey(Story(zork1))
        assertTrue(key.startsWith("r119-880429-"), key)
        val patched = zork1.copyOf()
        patched[0x1D] = (patched[0x1D] + 1).toByte()
        assertNotEquals(key, gameKey(Story(patched)))
    }

    @Test
    fun aFilledSlotAsksBeforeOverwriting() {
        val game = start()
        play(game, "north")
        menuSave(game, 3)
        play(game, "east")
        game.openPicker(SlotMode.SAVE)
        game.pickSlot(3)
        assertEquals(3, game.picker.value?.confirming)
        game.cancelPicker()
        assertNull(game.picker.value)
        menuRestore(game, 3)
        assertEquals("North of House", prompt(game).status?.location)
    }

    @Test
    fun confirmingOverwrites() {
        val game = start()
        play(game, "north")
        menuSave(game, 3)
        play(game, "east")
        game.openPicker(SlotMode.SAVE)
        game.pickSlot(3)
        game.pickSlot(3)
        await(game) { it.text().split("[Saved in slot 3.]").size == 3 }
        val slot = SaveStore(folder, gameKey(Story(zork1))).slots()[2]
        assertEquals("Behind House", slot.label)
    }

    @Test
    fun slotsShowALabelAndATime() {
        val game = start()
        play(game, "north")
        menuSave(game, 1)
        game.openPicker(SlotMode.RESTORE)
        val slots = assertNotNull(game.picker.value).slots
        assertEquals(SaveStore.SLOT_COUNT, slots.size)
        assertEquals("North of House", slots[0].label)
        assertTrue(slotTime(assertNotNull(slots[0].savedAt)).isNotBlank())
        assertEquals("Empty", slots[1].label)
    }

    @Test
    fun emptySlotsCannotBeRestored() {
        val game = start()
        prompt(game)
        game.openPicker(SlotMode.RESTORE)
        game.pickSlot(4)
        assertNotNull(game.picker.value)
    }

    @Test
    fun theGamesOwnSaveAndRestoreUseThePicker() {
        val game = start()
        play(game, "open mailbox", "take leaflet")
        game.send("save")
        assertTrue(awaitPicker(game).askedByGame)
        game.pickSlot(5)
        await(game) { it.waitingForInput && "Ok." in it.text() }
        play(game, "drop leaflet")
        val next = game.state.value.commandsTaken + 1
        game.send("restore")
        assertEquals(SlotMode.RESTORE, awaitPicker(game).mode)
        game.pickSlot(5)
        await(game) { it.waitingForInput && it.commandsTaken == next }
        assertTrue("A leaflet" in play(game, "inventory").text())
    }

    @Test
    fun cancellingTheGamesSaveReportsFailure() {
        val game = start()
        prompt(game)
        game.send("save")
        awaitPicker(game)
        game.cancelPicker()
        await(game) { it.waitingForInput && "Failed." in it.text() }
    }

    @Test
    fun menuUndoWorksInAVersion3Game() {
        val game = start()
        play(game, "open mailbox", "north")
        game.undo()
        val state = awaitMessage(game, "[Undone.]")
        assertEquals("West of House", state.status?.location)
        assertEquals(1, state.status?.second)
        game.undo()
        assertEquals(0, await(game) { it.status?.second == 0 && it.waitingForInput }.status?.second)
    }

    @Test
    fun undoWithNothingToUndoSaysSo() {
        val game = start()
        prompt(game)
        game.undo()
        awaitMessage(game, "[Nothing to undo.]")
    }

    @Test
    fun menuRestartStartsOver() {
        val game = start()
        play(game, "open mailbox", "north")
        game.restart()
        val state = await(game) { it.waitingForInput && it.status?.second == 0 }
        assertEquals("West of House", state.status?.location)
        assertFalse("North of House" in state.text(), state.text())
    }

    @Test
    fun lostPigResumesAfterLeaving() {
        val story = File("../story-files/LostPig.z8")
        if (!story.exists()) return
        val first = start(story.readBytes())
        play(first, "", "north", "inventory")
        leave(first)
        val second = start(story.readBytes())
        assertTrue("torch" in play(second, "inventory").text())
    }
}
