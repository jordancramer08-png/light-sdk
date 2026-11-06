package com.thelightphone.adventure.cli

import com.thelightphone.adventure.engine.Story
import com.thelightphone.adventure.engine.ZCapabilities
import com.thelightphone.adventure.engine.ZColor
import com.thelightphone.adventure.engine.ZCursor
import com.thelightphone.adventure.engine.ZIo
import com.thelightphone.adventure.engine.ZMachine
import com.thelightphone.adventure.engine.ZMachineError
import com.thelightphone.adventure.engine.ZStatusLine
import java.io.BufferedReader
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintStream
import kotlin.system.exitProcess

/** Typed input ran out, as when a script of commands is piped in. */
private class EndOfInput : RuntimeException()

/** ZIo for a text console. The upper window is kept as lines and shown before each prompt. */
class ConsoleIo(
    private val input: BufferedReader,
    private val out: PrintStream,
    private val echoInput: Boolean,
    private val saveFolder: File,
    private val storyName: String,
) : ZIo {
    override val capabilities = ZCapabilities(screenWidth = WIDTH, screenHeight = HEIGHT)

    private val pending = StringBuilder()
    private val upper = ArrayList<StringBuilder>()
    private var upperChanged = false
    private var inUpper = false
    private var wrapping = true
    private var line = 1
    private var column = 1

    override fun print(text: String) {
        if (inUpper) text.forEach(::putUpper) else printLower(text)
    }

    private fun printLower(text: String) {
        pending.append(text)
        while (true) {
            val end = pending.indexOf("\n")
            if (end < 0) break
            emitWrapped(pending.substring(0, end))
            pending.delete(0, end + 1)
        }
        if (!wrapping) flushPartial()
    }

    private fun emitWrapped(text: String) {
        var rest = text
        while (wrapping && rest.length > WIDTH) {
            val cut = rest.lastIndexOf(' ', WIDTH).let { if (it <= 0) WIDTH else it }
            out.println(rest.substring(0, cut))
            rest = rest.substring(cut).trimStart(' ')
        }
        out.println(rest)
    }

    private fun flushPartial() {
        out.print(pending)
        pending.clear()
        out.flush()
    }

    private fun putUpper(char: Char) {
        if (char == '\n') {
            line++
            column = 1
            return
        }
        if (line !in 1..upper.size || column !in 1..WIDTH) return
        val row = upper[line - 1]
        while (row.length < column) row.append(' ')
        row.setCharAt(column - 1, char)
        column++
        upperChanged = true
    }

    private fun beforeInput() {
        if (upperChanged) {
            val rows = upper.map { it.toString().trimEnd() }.filter { it.isNotEmpty() }
            rows.forEach { out.println("| $it") }
            upperChanged = false
        }
        flushPartial()
    }

    private fun nextLine(): String {
        beforeInput()
        val typed = input.readLine() ?: throw EndOfInput()
        if (echoInput) out.println(typed)
        return typed
    }

    override fun readLine(maxLength: Int, timeoutTenths: Int): String? = nextLine().take(maxLength)

    override fun readChar(timeoutTenths: Int): Int? = nextLine().firstOrNull()?.code ?: 13

    override fun showStatusLine(status: ZStatusLine) {
        val right = if (status.isTime) {
            "Time: %d:%02d".format(status.first, status.second)
        } else {
            "Score: ${status.first}  Moves: ${status.second}"
        }
        out.println("[ ${status.location.padEnd(WIDTH - right.length - 6)}  $right ]")
    }

    override fun splitWindow(lines: Int) {
        while (upper.size > lines) upper.removeAt(upper.size - 1)
        while (upper.size < lines) upper.add(StringBuilder())
    }

    override fun setWindow(window: Int) {
        inUpper = window == 1
        if (inUpper) {
            line = 1
            column = 1
        }
    }

    override fun eraseWindow(window: Int) {
        if (window == -1) upper.clear()
        upper.forEach { it.clear() }
    }

    override fun setCursor(line: Int, column: Int) {
        this.line = line
        this.column = column
    }

    override fun cursor() = ZCursor(line, column)

    override fun eraseLine() {
        upper.getOrNull(line - 1)?.let { if (it.length >= column) it.setLength(column - 1) }
    }

    override fun setBufferMode(buffered: Boolean) {
        wrapping = buffered
    }

    override fun setTextStyle(style: Int) = Unit

    override fun setColors(foreground: ZColor, background: ZColor) = Unit

    override fun chooseSaveSlot(): String? = askSlot("Save to slot: ")

    override fun chooseRestoreSlot(): String? = askSlot("Restore slot: ")

    private fun askSlot(prompt: String): String? {
        printLower(prompt)
        return nextLine().trim().ifEmpty { null }
    }

    private fun saveFile(slot: String): File {
        val safeName = slot.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(saveFolder, "$storyName-$safeName.qzl")
    }

    override fun writeSave(slot: String, data: ByteArray): Boolean = try {
        saveFolder.mkdirs()
        saveFile(slot).writeBytes(data)
        true
    } catch (error: IOException) {
        false
    }

    override fun readSave(slot: String): ByteArray? = try {
        saveFile(slot).readBytes()
    } catch (error: IOException) {
        null
    }

    /** Gaps are for the developer, so they go to the report file and not the game text. */
    override fun log(message: String) = Unit

    private companion object {
        const val WIDTH = 80
        const val HEIGHT = 24
    }
}

private fun seedFrom(args: Array<String>): Long? {
    val index = args.indexOf("--seed")
    return if (index >= 0) args.getOrNull(index + 1)?.toLongOrNull() else null
}

/** Adds this session's gaps to logs\<story>-gaps.txt and says how many there were. */
private fun writeGapReport(lines: List<String>, storyName: String, out: PrintStream) {
    if (lines.isEmpty()) return
    val file = File("logs", "$storyName-gaps.txt")
    file.parentFile.mkdirs()
    file.appendText("--- ${java.time.LocalDateTime.now()}\n" + lines.joinToString("\n") + "\n")
    out.println("[${lines.size} engine gaps noted in ${file.path}]")
}

/** Laptop-only program. It lives under src/test so it never ships in the APK. */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: play <story file> [--seed number]")
        exitProcess(2)
    }
    val out = PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8")
    val piped = System.console() == null
    val storyName = File(args[0]).nameWithoutExtension
    val reader = BufferedReader(InputStreamReader(System.`in`))
    val io = ConsoleIo(reader, out, echoInput = piped, saveFolder = File("saves"), storyName = storyName)
    try {
        val machine = ZMachine(Story(File(args[0]).readBytes()), io, seedFrom(args))
        try {
            machine.run()
        } catch (end: EndOfInput) {
            out.println()
        }
        writeGapReport(machine.gaps.report(), storyName, out)
    } catch (error: ZMachineError) {
        println("Cannot play ${args[0]}: ${error.message}")
        exitProcess(1)
    } catch (error: IOException) {
        println("Cannot read ${args[0]}: ${error.message}")
        exitProcess(1)
    }
}
