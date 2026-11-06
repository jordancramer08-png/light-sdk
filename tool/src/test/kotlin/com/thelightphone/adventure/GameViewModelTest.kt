package com.thelightphone.adventure

import com.thelightphone.adventure.engine.ZColor
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Runs real games through the view model, the same way the screen does. */
class GameViewModelTest {
    private val folder: File = Files.createTempDirectory("adventure-test").toFile()
    private val running = ArrayList<GameViewModel>()

    @AfterTest
    fun cleanUp() {
        running.forEach { it.stop() }
        folder.deleteRecursively()
    }

    private fun start(bytes: ByteArray) =
        GameViewModel(bytes, folder, seed = 42).also { running += it }

    private fun zork(): GameViewModel = start(File("src/main/assets/zork1.dat").readBytes())

    private fun awaitState(game: GameViewModel, ready: (GameUiState) -> Boolean) = runBlocking {
        withTimeout(20_000) { game.state.first(ready) }
    }

    private fun awaitPrompt(game: GameViewModel, commands: Int) =
        awaitState(game) { it.waitingForInput && it.commandsTaken == commands }

    private fun play(game: GameViewModel, vararg commands: String): GameUiState {
        var state = awaitPrompt(game, game.state.value.commandsTaken)
        commands.forEach { command ->
            val next = state.commandsTaken + 1
            game.send(command)
            state = awaitPrompt(game, next)
        }
        return state
    }

    private fun GameUiState.text() = lines.joinToString("\n") { line -> line.joinToString("") { it.text } }

    @Test
    fun zorkStartsAtWestOfHouse() {
        val state = play(zork())
        assertTrue("West of House" in state.text(), state.text())
        assertEquals("West of House", state.status?.location)
        assertEquals(emptyList(), state.upperRows)
    }

    @Test
    fun scriptedCommandsGiveTheRightOutput() {
        val state = play(zork(), "open the mailbox", "take the leaflet", "read it", "inventory", "go north")
        val text = state.text()
        assertTrue("Opening the small mailbox reveals a leaflet." in text, text)
        assertTrue("Taken." in text, text)
        assertTrue("WELCOME TO ZORK!" in text, text)
        assertTrue("A leaflet" in text, text)
        assertEquals("North of House", state.status?.location)
        assertEquals("Score: 0  Moves: 5", statusSummary(assertNotNull(state.status)))
    }

    @Test
    fun typedCommandsAreMarkedAsCommands() {
        val state = play(zork(), "inventory")
        val commands = state.lines.flatten().filter { it.kind == SpanKind.COMMAND }
        assertEquals(listOf("inventory"), commands.map { it.text })
        assertTrue(state.lines.flatten().filter { it.kind == SpanKind.STORY }.all { it.foreground == ZColor.DEFAULT })
    }

    @Test
    fun sendIsIgnoredOnceTheGameHasEnded() {
        val game = zork()
        play(game, "quit")
        game.send("y")
        val state = awaitState(game) { it.finished }
        assertFalse(state.waitingForInput)
        game.send("look")
        assertEquals(state.commandsTaken, game.state.value.commandsTaken)
    }

    @Test
    fun saveThenRestoreInANewGame() {
        val first = zork()
        play(first, "open the mailbox", "take the leaflet")
        first.send("save")
        runBlocking { withTimeout(20_000) { first.picker.first { it != null } } }
        first.pickSlot(1)
        val saved = awaitState(first) { it.waitingForInput && "Ok." in it.text() }
        assertTrue("Ok." in saved.text(), saved.text())
        first.send("quit")
        awaitPrompt(first, 4)
        first.send("y")
        awaitState(first) { it.finished }

        val second = zork()
        awaitPrompt(second, 0)
        second.send("restore")
        runBlocking { withTimeout(20_000) { second.picker.first { it != null } } }
        second.pickSlot(1)
        awaitPrompt(second, 1)
        val state = play(second, "inventory")
        assertTrue("A leaflet" in state.text(), state.text())
    }

    @Test
    fun gameColorsReachTheScreen() {
        val bytes = ByteArray(0x800)
        bytes[0] = 5
        bytes[0x06] = 0x04 // Code at 0x400.
        bytes[0x08] = 0x07 // Dictionary at 0x700.
        bytes[0x0C] = 0x02 // Globals at 0x200.
        bytes[0x0E] = 0x08 // All memory is dynamic.
        val hi = 0x8000 or (13 shl 10) or (14 shl 5) or 5
        // set_colour red green; print "hi"; quit
        val code = listOf(0x1B, 3, 4, 0xB2, hi shr 8, hi and 0xFF, 0xBA)
        code.forEachIndexed { index, value -> bytes[0x400 + index] = value.toByte() }
        val state = awaitState(start(bytes)) { it.finished }
        val span = state.lines.flatten().single()
        assertEquals(StorySpan("hi", foreground = ZColor.RED, background = ZColor.GREEN), span)
    }

    @Test
    fun laterVersionsFillTheUpperBand() {
        val story = File("../story-files/LostPig.z8")
        if (!story.exists()) return
        val state = play(start(story.readBytes()), "", "look")
        val band = state.upperRows.joinToString { row -> row.joinToString("") { it.text } }
        assertTrue("Outside" in band, band)
        assertEquals(null, state.status)
    }

    @Test
    fun upperWindowKeepsColumnsAndClips() {
        val window = UpperWindow(5)
        window.resize(2)
        window.moveCursor(2, 4)
        window.write("abcd", StorySpan(""))
        assertEquals("   ab", window.snapshot()[1].joinToString("") { it.text })
    }

    @Test
    fun storyLogJoinsMatchingText() {
        val log = StoryLog()
        log.append("one ", StorySpan(""))
        log.append("two\nthree", StorySpan(""))
        assertEquals(listOf("one two", "three"), log.snapshot().map { it.single().text })
    }

    @Test
    fun onlyTheWheelPressAndCameraKeysAreIgnored() {
        listOf(27, 80, 319).forEach { assertTrue(GameKeys.isIgnored(it)) }
        listOf(317, 318).forEach { assertFalse(GameKeys.isIgnored(it)) }
    }

    @Test
    fun textSizeCyclesAndHasADefault() {
        assertTrue(TextSizes.DEFAULT in TextSizes.choices)
        assertEquals(TextSizes.choices.first(), TextSizes.next(TextSizes.choices.last()))
    }
}
