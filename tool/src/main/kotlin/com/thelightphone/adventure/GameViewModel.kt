package com.thelightphone.adventure

import com.thelightphone.adventure.engine.Story
import com.thelightphone.adventure.engine.ZCapabilities
import com.thelightphone.adventure.engine.ZColor
import com.thelightphone.adventure.engine.ZCursor
import com.thelightphone.adventure.engine.ZIo
import com.thelightphone.adventure.engine.ZMachine
import com.thelightphone.adventure.engine.ZMachineError
import com.thelightphone.adventure.engine.ZStatusLine
import com.thelightphone.sdk.LightViewModel
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
 * [dataFolder] holds saves and the gap log. [gameId] names this game's save slot.
 */
class GameViewModel(
    private val storyBytes: ByteArray,
    private val dataFolder: File,
    private val gameId: String,
    private val seed: Long? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LightViewModel<Unit>() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val typed = LinkedBlockingQueue<Typed>()
    private val mutableState = MutableStateFlow(GameUiState())

    val state: StateFlow<GameUiState> = mutableState.asStateFlow()

    init {
        scope.launch { runGame() }
    }

    /** Passes on a typed command. Ignored unless the game is waiting for one. */
    fun send(command: String) {
        if (mutableState.value.waitingForInput) typed.put(Typed.Line(command))
    }

    /** Ends the game's coroutine. Called when the screen closes. */
    fun stop() {
        typed.put(Typed.Stop)
        scope.cancel()
    }

    override fun onCleared() = stop()

    private fun runGame() {
        val io = GameIo()
        try {
            ZMachine(Story(storyBytes), io, seed).run()
            io.publish(finished = true)
        } catch (stopped: GameStopped) {
            // The player left; nothing more to show.
        } catch (error: ZMachineError) {
            io.print("\n[This game cannot run: ${error.message}]\n")
            io.publish(finished = true)
        }
    }

    private sealed interface Typed {
        data class Line(val text: String) : Typed
        data object Stop : Typed
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

        fun publish(waiting: Boolean = false, finished: Boolean = false) {
            mutableState.update {
                it.copy(
                    lines = log.snapshot(),
                    status = status,
                    upperRows = upper.snapshot(),
                    waitingForInput = waiting,
                    commandsTaken = commandsTaken,
                    finished = finished,
                )
            }
        }

        override fun print(text: String) {
            if (inUpper) upper.write(text, look) else log.append(text, look)
        }

        /** Waits for the player. Returns null if [timeoutTenths] runs out first. */
        private fun nextTyped(timeoutTenths: Int): String? {
            publish(waiting = true)
            val item = if (timeoutTenths > 0) {
                typed.poll(timeoutTenths * 100L, TimeUnit.MILLISECONDS)
            } else {
                typed.take()
            }
            return when (item) {
                null -> null
                is Typed.Stop -> throw GameStopped()
                is Typed.Line -> item.text
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
            val line = nextTyped(timeoutTenths) ?: return null
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

        // The save menu comes in Session 7. Until then each game has one slot.
        override fun chooseSaveSlot(): String = gameId

        override fun chooseRestoreSlot(): String = gameId

        private fun saveFile(slot: String) = File(dataFolder, "saves/$slot.qzl")

        /** Writes a new file first, so a failed save cannot damage the last good one. */
        override fun writeSave(slot: String, data: ByteArray): Boolean = try {
            val file = saveFile(slot)
            file.parentFile?.mkdirs()
            val temporary = File(file.path + ".new")
            temporary.writeBytes(data)
            temporary.renameTo(file)
        } catch (error: IOException) {
            false
        }

        override fun readSave(slot: String): ByteArray? = try {
            saveFile(slot).readBytes()
        } catch (error: IOException) {
            null
        }

        override fun log(message: String) {
            try {
                dataFolder.mkdirs()
                File(dataFolder, "gaps.txt").appendText("$gameId: $message\n")
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
    }
}
