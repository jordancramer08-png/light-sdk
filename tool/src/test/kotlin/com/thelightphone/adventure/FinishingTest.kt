package com.thelightphone.adventure

import androidx.compose.ui.graphics.Color
import com.thelightphone.adventure.engine.RecordingIo
import com.thelightphone.adventure.engine.Story
import com.thelightphone.adventure.engine.ZMachine
import java.io.File
import java.nio.file.Files
import kotlin.math.pow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Settings, error handling, stuck games and the no-network rule. */
class FinishingTest {
    private val folder: File = Files.createTempDirectory("adventure-finish").toFile()
    private val zork = File("src/main/assets/zork1.dat").readBytes()
    private val running = ArrayList<GameViewModel>()

    @AfterTest
    fun cleanUp() {
        running.forEach(::leave)
        folder.deleteRecursively()
    }

    private fun start(bytes: ByteArray, autosave: Boolean = true) =
        GameViewModel(bytes, folder, seed = 42, autosaveEnabled = { autosave }).also { running += it }

    private fun leave(game: GameViewModel) {
        game.stop()
        runBlocking { withTimeout(20_000) { game.awaitEnd() } }
    }

    private fun await(game: GameViewModel, ready: (GameUiState) -> Boolean) = runBlocking {
        withTimeout(20_000) { game.state.first(ready) }
    }

    private fun play(game: GameViewModel, vararg commands: String): GameUiState {
        var state = await(game) { it.waitingForInput }
        commands.forEach { command ->
            val next = state.commandsTaken + 1
            game.send(command)
            state = await(game) { it.waitingForInput && it.commandsTaken == next }
        }
        return state
    }

    private fun GameUiState.text() = lines.joinToString("\n") { line -> line.joinToString("") { it.text } }

    /** A tiny version 5 story that runs [code] from address 0x400. */
    private fun story(code: List<Int>): ByteArray {
        val bytes = ByteArray(0x800)
        bytes[0] = 5
        bytes[0x06] = 0x04
        bytes[0x08] = 0x07
        bytes[0x0C] = 0x02
        bytes[0x0E] = 0x08
        code.forEachIndexed { index, value -> bytes[0x400 + index] = value.toByte() }
        return bytes
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Float) =
            if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (dark, light) = listOf(luminance(a), luminance(b)).sorted()
        return (light + 0.05) / (dark + 0.05)
    }

    @Test
    fun thereAreThreeThemesWithReadableText() {
        assertEquals(3, ThemePalettes.all.map { it.name }.toSet().size)
        ThemePalettes.all.forEach { theme ->
            assertTrue(contrast(theme.storyText, theme.background) >= 7, theme.name)
            assertTrue(contrast(theme.commandText, theme.background) >= 4.5, theme.name)
            assertTrue(contrast(theme.statusText, theme.statusBackground) >= 4.5, theme.name)
            assertTrue(contrast(theme.quickText, theme.buttonBackground) >= 4.5, theme.name)
            assertTrue(contrast(theme.verbText, theme.buttonBackground) >= 4.5, theme.name)
            assertTrue(contrast(theme.suggestionText, theme.buttonBackground) >= 4.5, theme.name)
        }
    }

    @Test
    fun themesCycleAndAnUnknownNameFallsBack() {
        assertEquals("Lantern", ThemePalettes.byName("nonsense").name)
        val names = generateSequence(ThemePalettes.lantern) { ThemePalettes.next(it.name) }.take(4).map { it.name }
        assertEquals(listOf("Lantern", "Parchment", "Deep Sea", "Lantern"), names.toList())
    }

    @Test
    fun autosaveOffWritesNothing() {
        val game = start(zork, autosave = false)
        play(game, "north")
        leave(game)
        assertEquals("West of House", await(start(zork), { it.waitingForInput }).status?.location)
    }

    @Test
    fun autosaveOffIgnoresAnOlderAutomaticSave() {
        val first = start(zork)
        play(first, "north")
        leave(first)
        assertEquals("West of House", await(start(zork, autosave = false), { it.waitingForInput }).status?.location)
    }

    @Test
    fun aDamagedFileShowsAPlainMessage() {
        val state = await(start(ByteArray(10))) { it.problem != null }
        assertEquals(GameViewModel.CANNOT_OPEN, state.problem)
    }

    @Test
    fun anUnsupportedVersionShowsAPlainMessage() {
        val state = await(start(ByteArray(100).also { it[0] = 6 })) { it.problem != null }
        assertEquals(GameViewModel.CANNOT_OPEN, state.problem)
    }

    @Test
    fun aBlorbWithNoStoryShowsAPlainMessage() {
        val file = "FORM".toByteArray() + byteArrayOf(0, 0, 0, 4) + "IFRS".toByteArray()
        assertEquals(GameViewModel.CANNOT_OPEN, await(start(file)) { it.problem != null }.problem)
    }

    @Test
    fun aFailingLoaderShowsAPlainMessageAndDoesNotCrash() {
        val game = GameViewModel({ error("disk gone") }, folder).also { running += it }
        assertEquals(GameViewModel.STOPPED, await(game) { it.problem != null }.problem)
    }

    @Test
    fun anEngineFailureKeepsTheAutomaticSave() {
        val good = start(zork)
        play(good, "north")
        leave(good)

        // The same game with its code replaced by an illegal return from the main routine.
        val broken = zork.copyOf()
        val pc = 0x1000
        broken[pc] = 0xB0.toByte()
        broken[0x06] = (pc shr 8).toByte()
        broken[0x07] = pc.toByte()
        // Stops the saved game resuming, so the broken code runs. The save file itself is untouched.
        val game = GameViewModel(broken, folder, seed = 42, autosaveEnabled = { false }).also { running += it }
        val state = await(game) { it.problem != null || it.waitingForInput }
        assertEquals(GameViewModel.STOPPED, state.problem)
        leave(game)

        assertEquals("North of House", await(start(zork), { it.waitingForInput }).status?.location)
    }

    @Test
    fun anErrorWhileRecoveringStopsCleanly() {
        // loadb storing to a local the main routine does not have, which fails twice.
        val machine = ZMachine(Story(story(listOf(0x10, 0, 0, 0x05))), RecordingIo())
        machine.run()
        assertTrue(machine.halted)
        assertNotNull(machine.failure)
    }

    @Test
    fun quittingStillClearsTheAutomaticSave() {
        val game = start(zork)
        play(game, "north", "quit")
        game.send("y")
        val state = await(game) { it.finished }
        assertNull(state.problem)
        leave(game)
        assertEquals("West of House", await(start(zork), { it.waitingForInput }).status?.location)
    }

    @Test
    fun aGameLoopingForeverShowsItsTextAndCanBeLeft() {
        val hi = 0x8000 or (13 shl 10) or (14 shl 5) or 5
        val game = start(story(listOf(0xB2, hi shr 8, hi and 0xFF, 0x8C, 0xFF, 0xFC)))
        val state = await(game) { it.lines.isNotEmpty() }
        assertFalse(state.waitingForInput)
        leave(game)
    }

    @Test
    fun aSilentLoopCanBeLeft() {
        val game = start(story(listOf(0x8C, 0xFF, 0xFF)))
        Thread.sleep(50)
        assertFalse(game.state.value.waitingForInput)
        leave(game)
    }

    @Test
    fun oneEndlessLineCannotGrowForever() {
        val log = StoryLog()
        repeat(100) { log.append("x".repeat(100), StorySpan("")) }
        assertTrue(log.snapshot().all { line -> line.sumOf { it.text.length } <= 4000 })
    }

    @Test
    fun anUpperWindowQuoteBoxIsKeptWhenTheWindowShrinks() {
        val window = UpperWindow(20)
        window.resize(4)
        window.moveCursor(3, 3)
        window.write("A quote", StorySpan(""))
        assertEquals(listOf("A quote"), window.resize(2))
    }

    @Test
    fun cursesQuoteBoxesReachTheStory() {
        val file = File("../story-files/curses.z5")
        if (!file.exists()) return
        val state = play(start(file.readBytes()), "", "", "", "look", "inventory")
        assertTrue("Dorothy Parker" in state.text(), state.text().takeLast(600))
        assertFalse("June 3rd, 1993" in state.text(), "the status band must not leak into the story")
    }

    @Test
    fun theUpperBandComesBackOnResume() {
        val file = File("../story-files/LostPig.z8")
        if (!file.exists()) return
        val bytes = file.readBytes()
        val first = start(bytes)
        val left = play(first, "", "look")
        leave(first)
        val state = await(start(bytes)) { it.waitingForInput }
        val band = { rows: List<TextLine> -> rows.map { row -> row.joinToString("") { it.text }.trim() } }
        assertTrue(band(left.upperRows).any { "Outside" in it })
        assertEquals(band(left.upperRows), band(state.upperRows))
    }

    @Test
    fun aVersion8GameOpensQuickly() {
        val file = File("../story-files/anchor.z8")
        if (!file.exists()) return
        val bytes = file.readBytes()
        val start = System.nanoTime()
        assertNotNull(await(start(bytes)) { it.waitingForInput })
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue(millis < 2_000, "took $millis ms")
    }

    @Test
    fun ourCodeUsesNoNetworkOrLocation() {
        val banned = Regex("""\b(ktor|okhttp|java\.net|HttpURLConnection|URLConnection|android\.location|LocationManager|fusedlocation)\b""", RegexOption.IGNORE_CASE)
        val found = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
            .flatMap { file -> file.readLines().filter(banned::containsMatchIn).map { "${file.name}: $it" } }
            .toList()
        assertEquals(emptyList(), found)
    }

    @Test
    fun theToolAsksOnlyForStorage() {
        val permissions = File("lighttool.toml").readLines().first { it.startsWith("permissions") }
        assertEquals("""permissions = ["android.permission.MANAGE_EXTERNAL_STORAGE"]""", permissions)
    }
}
