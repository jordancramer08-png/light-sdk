package com.thelightphone.adventure.engine

/** Thrown when a test's commands run out, to stop the game. */
class ScriptFinished : RuntimeException()

/** A ZIo that plays a list of commands and remembers everything the game did. */
class RecordingIo(
    commands: List<String> = emptyList(),
    private val slotsFromScript: Boolean = false,
) : ZIo {
    private val remaining = ArrayDeque(commands)
    val output = StringBuilder()
    val logs = ArrayList<String>()
    val statusLines = ArrayList<ZStatusLine>()
    val saves = HashMap<String, ByteArray>()
    var slotRequests = 0
    var eraseLineCalls = 0

    /** Logged problems, leaving out stubs and oddities the engine handled. */
    val failures: List<String>
        get() = logs.filter { it.startsWith("unimplemented") || it.startsWith("error") || it.startsWith("cannot") }

    private fun next() = remaining.removeFirstOrNull() ?: throw ScriptFinished()

    override fun print(text: String) {
        output.append(text)
    }

    override fun readLine(maxLength: Int, timeoutTenths: Int): String? {
        val line = next()
        output.append(line).append('\n')
        return line
    }

    override fun readChar(timeoutTenths: Int): Int? = next().firstOrNull()?.code ?: 13

    override fun showStatusLine(status: ZStatusLine) {
        statusLines += status
    }

    override fun splitWindow(lines: Int) = Unit
    override fun setWindow(window: Int) = Unit
    override fun eraseWindow(window: Int) = Unit
    override fun setCursor(line: Int, column: Int) = Unit
    override fun cursor() = ZCursor(1, 1)
    override fun eraseLine() {
        eraseLineCalls++
    }

    override fun setBufferMode(buffered: Boolean) = Unit
    override fun setTextStyle(style: Int) = Unit
    override fun setColors(foreground: ZColor, background: ZColor) = Unit

    override fun chooseSaveSlot(): String? {
        slotRequests++
        return if (slotsFromScript) next() else "slot"
    }

    override fun chooseRestoreSlot(): String? = chooseSaveSlot()

    override fun writeSave(slot: String, data: ByteArray): Boolean {
        saves[slot] = data
        return true
    }

    override fun readSave(slot: String): ByteArray? = saves[slot]

    override fun log(message: String) {
        logs += message
    }
}
