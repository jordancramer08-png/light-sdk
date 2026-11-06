package com.thelightphone.adventure

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A button that sends [command] straight to the game. */
data class QuickButton(val label: String, val command: String)

/** The fixed contents of the button rows. */
object QuickButtons {
    val commands = listOf(
        QuickButton("N", "north"),
        QuickButton("S", "south"),
        QuickButton("E", "east"),
        QuickButton("W", "west"),
        QuickButton("NE", "northeast"),
        QuickButton("NW", "northwest"),
        QuickButton("SE", "southeast"),
        QuickButton("SW", "southwest"),
        QuickButton("Up", "up"),
        QuickButton("Down", "down"),
        QuickButton("In", "in"),
        QuickButton("Out", "out"),
        QuickButton("Look", "look"),
        QuickButton("Inventory", "inventory"),
        QuickButton("Wait", "wait"),
        QuickButton("Again", "again"),
    )

    val verbs = listOf("Take", "Drop", "Open", "Close", "Examine", "Read", "Push", "Pull", "Unlock")
}

/** What fills the story area instead of the story. */
enum class GamePanel { NONE, HISTORY, MENU }

/** The input line and everything that helps fill it. History is newest first. */
data class TypingState(
    val text: String = "",
    val suggestions: List<String> = emptyList(),
    val history: List<String> = emptyList(),
    val panel: GamePanel = GamePanel.NONE,
)

/** The word in [text] at [offset], without any punctuation, or null if there is none there. */
fun wordAt(text: String, offset: Int): String? {
    val at = listOf(offset, offset - 1).firstOrNull { text.getOrNull(it)?.isLetterOrDigit() == true }
        ?: return null
    var start = at
    var end = at + 1
    while (start > 0 && isWordChar(text[start - 1])) start--
    while (end < text.length && isWordChar(text[end])) end++
    return text.substring(start, end).trim('\'', '-').ifEmpty { null }
}

private fun isWordChar(char: Char) = char.isLetterOrDigit() || char == '\'' || char == '-'

/**
 * Suggests words from a game's dictionary. Dictionaries cut words to [keyLength] letters, so
 * "mailbox" is stored as "mailbo". Any full word the game has printed is shown in its place.
 * Either form works, because the game only compares the first [keyLength] letters.
 */
class WordSuggester(dictionary: List<String>, private val keyLength: Int) {
    private val words = dictionary.filter { PLAIN_WORD.matches(it) }.distinct().sorted()
    private val printed = ConcurrentHashMap.newKeySet<String>()

    fun notePrinted(text: String) {
        LETTERS.findAll(text.lowercase()).forEach { if (it.value.length > keyLength) printed += it.value }
    }

    fun suggest(prefix: String): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val key = prefix.lowercase()
        return words.asSequence()
            .filter { it.startsWith(key.take(keyLength)) }
            .flatMap(::fullForms)
            .filter { it.startsWith(key) && it != key }
            .distinct()
            .take(MAX_SUGGESTIONS)
            .toList()
    }

    private fun fullForms(word: String): List<String> {
        if (word.length < keyLength) return listOf(word)
        return printed.filter { it.startsWith(word) }.sorted().ifEmpty { listOf(word) }
    }

    private companion object {
        val PLAIN_WORD = Regex("[a-z][a-z'-]+")
        val LETTERS = Regex("[a-z]+")
        const val MAX_SUGGESTIONS = 8
    }
}

/** All the rules for the input line. Plain Kotlin, so tests need no screen. */
class TypingAids {
    private val mutableState = MutableStateFlow(TypingState())

    @Volatile
    private var suggester: WordSuggester? = null

    val state: StateFlow<TypingState> = mutableState.asStateFlow()

    /** Called once the game is loaded. */
    fun useDictionary(words: List<String>, keyLength: Int) {
        suggester = WordSuggester(words, keyLength)
        setText(mutableState.value.text)
    }

    fun notePrinted(text: String) {
        suggester?.notePrinted(text)
    }

    private fun setText(text: String) {
        val suggestions = suggester?.suggest(lastWord(text)) ?: emptyList()
        mutableState.update { it.copy(text = text, suggestions = suggestions) }
    }

    private fun lastWord(text: String) = text.substringAfterLast(' ')

    fun type(typed: String) = setText(mutableState.value.text + typed)

    fun backspace() = setText(mutableState.value.text.dropLast(1))

    fun clear() = setText("")

    /** Adds a whole word after what is there, then a space ready for the next word. */
    fun addWord(word: String) {
        val text = mutableState.value.text
        val gap = if (text.isEmpty() || text.endsWith(' ')) "" else " "
        setText("$text$gap${word.lowercase()} ")
    }

    /** Replaces the word being typed with a suggestion. */
    fun complete(word: String) {
        setText(mutableState.value.text.substringBeforeLast(' ', "").let { before ->
            if (before.isEmpty()) "$word " else "$before $word "
        })
    }

    fun rememberCommand(command: String) {
        val clean = command.trim()
        if (clean.isEmpty()) return
        mutableState.update { state ->
            val history = if (state.history.firstOrNull() == clean) state.history else listOf(clean) + state.history
            state.copy(history = history.take(MAX_HISTORY))
        }
    }

    /** Fills the input line with an old command and goes back to the story. */
    fun pickFromHistory(command: String) {
        setText(command)
        closePanel()
    }

    /** Opens a panel, or closes it if it is already open. */
    fun togglePanel(panel: GamePanel) {
        mutableState.update { it.copy(panel = if (it.panel == panel) GamePanel.NONE else panel) }
    }

    fun closePanel() {
        mutableState.update { it.copy(panel = GamePanel.NONE) }
    }

    private companion object {
        const val MAX_HISTORY = 20
    }
}
