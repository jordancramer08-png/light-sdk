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

    override fun chooseSaveSlot(): String? = askSlot("Save to slot (nothing is stored yet): ")

    override fun chooseRestoreSlot(): String? = askSlot("Restore slot (nothing is loaded yet): ")

    private fun askSlot(prompt: String): String? {
        printLower(prompt)
        return nextLine().trim().ifEmpty { null }
    }

    override fun log(message: String) {
        flushPartial()
        System.err.println("[engine] $message")
    }

    private companion object {
        const val WIDTH = 80
        const val HEIGHT = 24
    }
}

private fun seedFrom(args: Array<String>): Long? {
    val index = args.indexOf("--seed")
    return if (index >= 0) args.getOrNull(index + 1)?.toLongOrNull() else null
}

/** Laptop-only program. It lives under src/test so it never ships in the APK. */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: play <story file> [--seed number]")
        exitProcess(2)
    }
    val out = PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8")
    val piped = System.console() == null
    val io = ConsoleIo(BufferedReader(InputStreamReader(System.`in`)), out, echoInput = piped)
    try {
        ZMachine(Story(File(args[0]).readBytes()), io, seedFrom(args)).run()
    } catch (end: EndOfInput) {
        out.println()
    } catch (error: ZMachineError) {
        println("Cannot play ${args[0]}: ${error.message}")
        exitProcess(1)
    } catch (error: IOException) {
        println("Cannot read ${args[0]}: ${error.message}")
        exitProcess(1)
    }
}
