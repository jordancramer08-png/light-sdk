package com.thelightphone.adventure

import com.thelightphone.adventure.engine.Story
import com.thelightphone.adventure.engine.ZCapabilities
import com.thelightphone.adventure.engine.ZColor
import com.thelightphone.adventure.engine.ZCursor
import com.thelightphone.adventure.engine.ZInputCancelled
import com.thelightphone.adventure.engine.ZIo
import com.thelightphone.adventure.engine.ZMachine
import com.thelightphone.adventure.engine.ZMachineError
import com.thelightphone.adventure.engine.ZStatusLine
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import java.io.File
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs one game on a background coroutine. The screen reads [state] and calls [send]; the
 * engine only ever sees [GameIo], so the two never touch each other directly.
 *
 * [dataFolder] holds saves and the gap log. Saves are in a folder made from the game's identity.
 */
class GameViewModel(
    private val storyBytes: ByteArray,
    private val dataFolder: File,
    private val seed: Long? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LightViewModel<Unit>() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val typed = LinkedBlockingQueue<Typed>()
    private val mutableState = MutableStateFlow(GameUiState())
    private val mutablePicker = MutableStateFlow<SlotPicker?>(null)

    @Volatile
    private var saves: SaveStore? = null

    val state: StateFlow<GameUiState> = mutableState.asStateFlow()

    /** The slot picker, while it is open. */
    val picker: StateFlow<SlotPicker?> = mutablePicker.asStateFlow()

    /** The input line, suggestions, history and open panel. All their rules are in here. */
    val typing = TypingAids()

    private val gameJob = scope.launch { runGame() }

    /** Passes on a command. Ignored unless the game is waiting for one. */
    fun send(command: String) {
        val game = mutableState.value
        if (!game.waitingForInput) return
        if (!game.waitingForKey) typing.rememberCommand(command)
        typing.closePanel()
        typed.put(Typed.Line(command))
    }

    /** Sends what is in the input line, and empties it if the game took it. */
    fun submit() {
        if (!mutableState.value.waitingForInput) return
        send(typing.state.value.text.trim())
        typing.clear()
    }

    /** Saves automatically, the next time the game is waiting for input. */
    fun autosave() {
        typed.put(Typed.AutoSave)
    }

    /** Saves automatically, then ends the game's coroutine. Called when the screen closes. */
    fun stop() {
        autosave()
        typed.put(Typed.Stop)
        scope.cancel()
    }

    /** Waits for the game's coroutine to end after [stop]. */
    suspend fun awaitEnd() = gameJob.join()

    override fun onCleared() = stop()

    override fun onAppPause() = autosave()

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) = autosave()

    /** Opens the slot picker from the menu. Needs the game to be waiting for input. */
    fun openPicker(mode: SlotMode) {
        if (!mutableState.value.waitingForInput) return
        typing.closePanel()
        mutablePicker.value = SlotPicker(mode, saves?.slots() ?: emptyList())
    }

    /** A tap on a slot. Empty slots cannot be restored; filled ones ask before overwriting. */
    fun pickSlot(number: Int) {
        val picker = mutablePicker.value ?: return
        val filled = picker.slots.firstOrNull { it.number == number }?.savedAt != null
        when {
            picker.mode == SlotMode.RESTORE && !filled -> Unit
            picker.mode == SlotMode.SAVE && filled && picker.confirming != number ->
                mutablePicker.value = picker.copy(confirming = number)
            else -> finishPicking(picker, number)
        }
    }

    private fun finishPicking(picker: SlotPicker, number: Int) {
        mutablePicker.value = null
        typed.put(
            when {
                picker.askedByGame -> Typed.SlotChoice(number)
                picker.mode == SlotMode.SAVE -> Typed.SaveTo(number)
                else -> Typed.RestoreFrom(number)
            },
        )
    }

    /** Closes the picker. A game that asked for a slot is told the player cancelled. */
    fun cancelPicker() {
        val picker = mutablePicker.value ?: return
        mutablePicker.value = null
        if (picker.askedByGame) typed.put(Typed.SlotChoice(null))
    }

    /** Goes back one turn. Works in every game, including those with no undo of their own. */
    fun undo() {
        typing.closePanel()
        if (mutableState.value.waitingForInput) typed.put(Typed.Undo)
    }

    fun restart() {
        typing.closePanel()
        if (mutableState.value.waitingForInput) typed.put(Typed.Restart)
    }

    private fun runGame() {
        val io = GameIo()
        try {
            val story = Story(storyBytes)
            val store = SaveStore(dataFolder, gameKey(story))
            saves = store
            typing.useDictionary(dictionaryWords(story), story.text.dictionaryZChars)
            val machine = ZMachine(story, io, seed)
            io.begin(machine, store)
            machine.run()
            store.clearAuto()
            io.publish(finished = true)
        } catch (stopped: GameStopped) {
            // The player left; nothing more to show.
        } catch (error: ZMachineError) {
            io.print("\n[This game cannot run: ${error.message}]\n")
            io.publish(finished = true)
        }
    }

    /** A damaged dictionary only costs the suggestions, not the game. */
    private fun dictionaryWords(story: Story): List<String> = try {
        story.dictionary.words()
    } catch (error: ZMachineError) {
        emptyList()
    }

    private sealed interface Typed {
        data class Line(val text: String) : Typed
        data object Stop : Typed
        data object AutoSave : Typed
        data object Undo : Typed
        data object Restart : Typed
        data class SaveTo(val slot: Int) : Typed
        data class RestoreFrom(val slot: Int) : Typed
        data class SlotChoice(val slot: Int?) : Typed
    }

    private class GameStopped : RuntimeException()

    /** The only link between the engine and the outside world. Runs on the game's thread. */
    private inner class GameIo : ZIo {
        override val capabilities = ZCapabilities(
            screenWidth = UPPER_COLUMNS,
            screenHeight = SCREEN_LINES,
            color = true,
            bold = true,
            italic = true,
            timedInput = true,
        )

        private val log = StoryLog()
        private val upper = UpperWindow(UPPER_COLUMNS)
        private var look = StorySpan("")
        private var inUpper = false
        private var status: ZStatusLine? = null
        private var commandsTaken = 0
        private lateinit var machine: ZMachine
        private lateinit var store: SaveStore

        /** One save per recent turn, oldest first, for the menu's Undo. */
        private val turns = ArrayDeque<ByteArray>()
        private var lastTurnKept = -1

        /** Connects the running game, and resumes from the automatic save if there is one. */
        fun begin(machine: ZMachine, store: SaveStore) {
            this.machine = machine
            this.store = store
            val saved = store.readAuto() ?: return
            if (machine.loadSaved(saved)) log.append(store.readAutoText(), StorySpan(""))
        }

        fun publish(waiting: Boolean = false, forKey: Boolean = false, finished: Boolean = false) {
            mutableState.update {
                it.copy(
                    lines = log.snapshot(),
                    status = status,
                    upperRows = upper.snapshot(),
                    waitingForInput = waiting,
                    waitingForKey = forKey,
                    commandsTaken = commandsTaken,
                    finished = finished,
                )
            }
        }

        override fun print(text: String) {
            if (inUpper) upper.write(text, look) else log.append(text, look)
            typing.notePrinted(text)
        }

        /** Waits for the player, doing any menu actions. Null if [timeoutTenths] runs out. */
        private fun nextTyped(timeoutTenths: Int, forKey: Boolean = false): String? {
            keepTurnForUndo()
            while (true) {
                publish(waiting = true, forKey = forKey)
                val item = if (timeoutTenths > 0) {
                    typed.poll(timeoutTenths * 100L, TimeUnit.MILLISECONDS)
                } else {
                    typed.take()
                }
                when (item) {
                    null -> return null
                    is Typed.Line -> return item.text
                    else -> doMenuAction(item)
                }
            }
        }

        private fun keepTurnForUndo() {
            if (lastTurnKept == commandsTaken) return
            lastTurnKept = commandsTaken
            machine.saveAtInput()?.let(turns::addLast)
            if (turns.size > MAX_UNDO_TURNS) turns.removeFirst()
        }

        private fun doMenuAction(action: Typed) {
            when (action) {
                is Typed.Stop -> throw GameStopped()
                is Typed.AutoSave -> saveAutomatically()
                is Typed.SaveTo -> saveToSlot(action.slot)
                is Typed.RestoreFrom -> restoreSlot(action.slot)
                is Typed.Undo -> undoTurn()
                is Typed.Restart -> restartGame()
                else -> Unit
            }
        }

        private fun saveAutomatically() {
            val data = machine.saveAtInput() ?: return
            val recent = log.snapshot().takeLast(RESUME_LINES)
            store.writeAuto(data, recent.joinToString("\n") { line -> line.joinToString("") { it.text } })
        }

        private fun note(message: String) {
            log.append("\n$message\n", StorySpan(""))
        }

        private fun saveToSlot(slot: Int) {
            val saved = machine.saveAtInput()?.let { store.write(slot, it, slotLabel()) } == true
            note(if (saved) "[Saved in slot $slot.]" else "[Could not save.]")
        }

        private fun slotLabel(): String {
            val upperText = upper.snapshot().firstOrNull()?.joinToString("") { it.text }
            val text = status?.location ?: upperText?.trim()?.replace(Regex(" {2,}"), ", ")
            return text?.ifEmpty { null }?.take(MAX_LABEL) ?: "Saved game"
        }

        private fun restoreSlot(slot: Int) {
            val data = store.read(slot)
            if (data == null || !machine.loadSaved(data)) {
                note("[Could not restore slot $slot.]")
                return
            }
            note("[Restored slot $slot.]")
            replacedGame()
        }

        private fun undoTurn() {
            if (turns.size < 2) {
                note("[Nothing to undo.]")
                return
            }
            turns.removeLast()
            val earlier = turns.removeLast()
            machine.loadSaved(earlier)
            note("[Undone.]")
            lastTurnKept = -1
            throw ZInputCancelled()
        }

        private fun restartGame() {
            log.clear()
            machine.restartNow()
            replacedGame()
        }

        /** After the whole game changes, older turns can no longer be undone. */
        private fun replacedGame() {
            turns.clear()
            lastTurnKept = -1
            throw ZInputCancelled()
        }

        /** Shows the slot picker because the game itself asked, and waits for the answer. */
        private fun askForSlot(mode: SlotMode): String? {
            mutablePicker.value = SlotPicker(mode, store.slots(), askedByGame = true)
            publish()
            while (true) {
                when (val item = typed.take()) {
                    is Typed.Stop -> throw GameStopped()
                    is Typed.SlotChoice -> return item.slot?.toString()
                    else -> Unit
                }
            }
        }

        override fun readLine(maxLength: Int, timeoutTenths: Int): String? {
            val line = nextTyped(timeoutTenths)?.take(maxLength) ?: return null
            log.append(line + "\n", look.copy(kind = SpanKind.COMMAND))
            commandsTaken++
            publish()
            return line
        }

        /** A key press is a Send on this screen: the first letter, or Return if empty. */
        override fun readChar(timeoutTenths: Int): Int? {
            val line = nextTyped(timeoutTenths, forKey = true) ?: return null
            commandsTaken++
            publish()
            return line.firstOrNull()?.code ?: ZSCII_RETURN
        }

        override fun showStatusLine(status: ZStatusLine) {
            this.status = status
        }

        override fun splitWindow(lines: Int) = upper.resize(lines)

        override fun setWindow(window: Int) {
            inUpper = window == 1
            if (inUpper) upper.moveCursor(1, 1)
        }

        override fun eraseWindow(window: Int) {
            if (window == -1) upper.resize(0)
            if (window == 1 || window < 0) upper.clear()
            if (window == 0 || window < 0) log.clear()
        }

        override fun setCursor(line: Int, column: Int) = upper.moveCursor(line, column)

        override fun cursor() = ZCursor(upper.line, upper.column)

        override fun eraseLine() = upper.eraseToEndOfLine()

        override fun setBufferMode(buffered: Boolean) = Unit // Compose always wraps lower text.

        override fun setTextStyle(style: Int) {
            look = look.copy(style = if (style == 0) 0 else look.style or style)
        }

        override fun setColors(foreground: ZColor, background: ZColor) {
            look = look.copy(
                foreground = if (foreground == ZColor.CURRENT) look.foreground else foreground,
                background = if (background == ZColor.CURRENT) look.background else background,
            )
        }

        override fun chooseSaveSlot(): String? = askForSlot(SlotMode.SAVE)

        override fun chooseRestoreSlot(): String? = askForSlot(SlotMode.RESTORE)

        override fun writeSave(slot: String, data: ByteArray): Boolean =
            store.write(slot.toInt(), data, slotLabel())

        override fun readSave(slot: String): ByteArray? = store.read(slot.toInt())

        override fun log(message: String) {
            try {
                dataFolder.mkdirs()
                File(dataFolder, "gaps.txt").appendText("$message\n")
            } catch (error: IOException) {
                // The gap log must never stop a game.
            }
        }
    }

    companion object {
        /** Columns games are told about. The upper band scales its font to fit them. */
        const val UPPER_COLUMNS = 40
        private const val SCREEN_LINES = 24
        private const val ZSCII_RETURN = 13
        private const val MAX_UNDO_TURNS = 10
        private const val RESUME_LINES = 40
        private const val MAX_LABEL = 32
    }
}
