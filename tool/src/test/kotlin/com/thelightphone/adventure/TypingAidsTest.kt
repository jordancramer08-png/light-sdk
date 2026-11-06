package com.thelightphone.adventure

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class TypingAidsTest {
    private val folder: File = Files.createTempDirectory("adventure-typing").toFile()
    private val running = ArrayList<GameViewModel>()

    @AfterTest
    fun cleanUp() {
        running.forEach { it.stop() }
        folder.deleteRecursively()
    }

    private fun aids(vararg words: String, keyLength: Int = 6) =
        TypingAids().also { it.useDictionary(words.toList(), keyLength) }

    private fun TypingAids.text() = state.value.text

    private fun zork() = GameViewModel(File("src/main/assets/zork1.dat").readBytes(), folder, seed = 42)
        .also { running += it }

    private fun awaitTurn(game: GameViewModel, commands: Int) = runBlocking {
        withTimeout(20_000) { game.state.first { it.waitingForInput && it.commandsTaken == commands } }
    }

    @Test
    fun buttonRowsHaveEverythingAskedFor() {
        val labels = QuickButtons.commands.map { it.label }
        val wanted = "N S E W NE NW SE SW Up Down In Out Look Inventory Wait Again".split(" ")
        assertEquals(wanted, labels)
        assertEquals("Take Drop Open Close Examine Read Push Pull Unlock".split(" "), QuickButtons.verbs)
    }

    @Test
    fun aVerbGoesInWithASpaceAfterIt() {
        val typing = aids()
        typing.addWord("Take")
        assertEquals("take ", typing.text())
    }

    @Test
    fun addedWordsAreSeparatedOnce() {
        val typing = aids()
        typing.type("open")
        typing.addWord("Mailbox")
        typing.addWord("now")
        assertEquals("open mailbox now ", typing.text())
    }

    @Test
    fun suggestionsFollowTheWordBeingTyped() {
        val typing = aids("take", "tackle", "lamp")
        typing.type("open ta")
        assertEquals(listOf("tackle", "take"), typing.state.value.suggestions)
        typing.type("k")
        assertEquals(listOf("take"), typing.state.value.suggestions)
        typing.backspace()
        assertEquals(2, typing.state.value.suggestions.size)
    }

    @Test
    fun noSuggestionsAfterASpaceOrForAWholeWord() {
        val typing = aids("take", "taken")
        typing.type("take ")
        assertEquals(emptyList(), typing.state.value.suggestions)
        typing.clear()
        typing.type("take")
        assertEquals(listOf("taken"), typing.state.value.suggestions)
    }

    @Test
    fun aSuggestionCompletesOnlyTheLastWord() {
        val typing = aids("lamp")
        typing.type("take la")
        typing.complete("lamp")
        assertEquals("take lamp ", typing.text())
        assertEquals(emptyList(), typing.state.value.suggestions)
    }

    @Test
    fun aSuggestionCompletesAFirstWord() {
        val typing = aids("lamp")
        typing.type("la")
        typing.complete("lamp")
        assertEquals("lamp ", typing.text())
    }

    @Test
    fun oddDictionaryEntriesAreNotSuggested() {
        val typing = aids("$#", ",", "a", "don't", "x-ray")
        typing.type("d")
        assertEquals(listOf("don't"), typing.state.value.suggestions)
        typing.clear()
        typing.type("$")
        assertEquals(emptyList(), typing.state.value.suggestions)
    }

    @Test
    fun suggestionsAreCappedAtEight() {
        val typing = aids(*Array(20) { "word" + ('a' + it) })
        typing.type("wo")
        assertEquals(8, typing.state.value.suggestions.size)
    }

    @Test
    fun printedWordsReplaceTruncatedOnes() {
        val typing = aids("mailbo")
        typing.type("mai")
        assertEquals(listOf("mailbo"), typing.state.value.suggestions)
        typing.notePrinted("There is a small Mailbox here.")
        typing.backspace()
        typing.type("i")
        assertEquals(listOf("mailbox"), typing.state.value.suggestions)
    }

    @Test
    fun historyKeepsTheLastTwentyNewestFirst() {
        val typing = aids()
        repeat(25) { typing.rememberCommand("command $it") }
        val history = typing.state.value.history
        assertEquals(20, history.size)
        assertEquals("command 24", history.first())
        assertEquals("command 5", history.last())
    }

    @Test
    fun historySkipsBlanksAndRepeatsInARow() {
        val typing = aids()
        listOf("look", "  ", "look", "north", "look").forEach(typing::rememberCommand)
        assertEquals(listOf("look", "north", "look"), typing.state.value.history)
    }

    @Test
    fun pickingFromHistoryFillsTheLineAndClosesThePanel() {
        val typing = aids()
        typing.togglePanel(GamePanel.HISTORY)
        assertEquals(GamePanel.HISTORY, typing.state.value.panel)
        typing.pickFromHistory("take lamp")
        assertEquals("take lamp", typing.text())
        assertEquals(GamePanel.NONE, typing.state.value.panel)
    }

    @Test
    fun panelsToggleAndReplaceEachOther() {
        val typing = aids()
        typing.togglePanel(GamePanel.MENU)
        typing.togglePanel(GamePanel.HISTORY)
        assertEquals(GamePanel.HISTORY, typing.state.value.panel)
        typing.togglePanel(GamePanel.HISTORY)
        assertEquals(GamePanel.NONE, typing.state.value.panel)
    }

    @Test
    fun wordAtFindsWordsNotPunctuation() {
        val text = "the small mailbox, don't."
        assertEquals("the", wordAt(text, 0))
        assertEquals("mailbox", wordAt(text, 12))
        assertEquals("mailbox", wordAt(text, 17)) // Just after the last letter.
        assertEquals("don't", wordAt(text, 21))
        assertNull(wordAt(text, 18))
        assertNull(wordAt("", 0))
        assertNull(wordAt("...", 1))
    }

    @Test
    fun aQuickButtonSendsAtOnceAndKeepsTheInputLine() {
        val game = zork()
        awaitTurn(game, 0)
        game.typing.type("take")
        game.send("north")
        val state = awaitTurn(game, 1)
        assertEquals("North of House", state.status?.location)
        assertEquals("take", game.typing.state.value.text)
        assertEquals(listOf("north"), game.typing.state.value.history)
    }

    @Test
    fun submitSendsTheLineEmptiesItAndRemembersIt() {
        val game = zork()
        awaitTurn(game, 0)
        game.typing.addWord("Open")
        game.typing.addWord("mailbox")
        game.submit()
        val state = awaitTurn(game, 1)
        assertTrue(state.lines.flatten().any { "reveals a leaflet" in it.text })
        assertEquals("", game.typing.state.value.text)
        assertEquals(listOf("open mailbox"), game.typing.state.value.history)
    }

    @Test
    fun zorkDictionarySuggestsRealWords() {
        val game = zork()
        awaitTurn(game, 0)
        game.typing.type("mail")
        assertEquals(listOf("mailbox"), game.typing.state.value.suggestions)
        game.typing.complete("mailbox")
        game.typing.clear()
        game.typing.type("inven")
        assertTrue(game.typing.state.value.suggestions.any { it.startsWith("invent") })
    }

    @Test
    fun submitKeepsTheLineWhileTheGameIsBusy() {
        val game = zork()
        val state = awaitTurn(game, 0)
        game.send("quit")
        awaitTurn(game, 1)
        game.send("y")
        runBlocking { withTimeout(20_000) { game.state.first { it.finished } } }
        game.typing.type("look")
        game.submit()
        assertEquals("look", game.typing.state.value.text)
        assertFalse(state.finished)
    }
}
