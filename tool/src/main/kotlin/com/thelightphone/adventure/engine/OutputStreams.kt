// Written from the Z-Machine Standards Document 1.1. No code is ported from JSZM or ifvms.js.
package com.thelightphone.adventure.engine

/**
 * Output streams (Standard section 7): 1 screen, 2 transcript, 3 memory table, 4 commands.
 * While a memory table is open it receives everything and the other streams get nothing.
 */
class OutputStreams(
    private val memory: StoryMemory,
    private val text: ZText,
    private val io: ZIo,
) {
    private class MemoryTable(val address: Int) {
        var count = 0
    }

    private val tables = ArrayList<MemoryTable>()
    private var screenOn = true

    /** Stream 2 follows header Flags 2 bit 0, which games may also set directly. */
    private val transcriptOn: Boolean get() = memory.readByte(FLAGS2_LOW) and 1 != 0

    fun reset() {
        tables.clear()
        screenOn = true
    }

    fun print(value: String) {
        val table = tables.lastOrNull()
        if (table != null) {
            writeToTable(table, value)
            return
        }
        if (screenOn) io.print(value)
        if (transcriptOn) io.transcript(value)
    }

    /** Copies typed input to the transcript. The screen shows it already. */
    fun echoInput(line: String) {
        if (tables.isEmpty() && transcriptOn) io.transcript(line + "\n")
    }

    private fun writeToTable(table: MemoryTable, value: String) {
        value.codePoints().forEach { codePoint ->
            memory.writeByte(table.address + 2 + table.count, text.unicodeToZscii(codePoint))
            table.count++
        }
    }

    /** The output_stream opcode. Positive numbers select a stream, negative deselect it. */
    fun select(number: Int, tableAddress: Int) {
        when (number) {
            1 -> screenOn = true
            -1 -> screenOn = false
            2 -> setTranscript(true)
            -2 -> setTranscript(false)
            3 -> openTable(tableAddress)
            -3 -> closeTable()
            // Stream 4 records commands. Nothing reads it yet, so selecting it changes nothing.
        }
    }

    private fun setTranscript(on: Boolean) {
        val flags = memory.readByte(FLAGS2_LOW)
        memory.writeByte(FLAGS2_LOW, if (on) flags or 1 else flags and 1.inv())
    }

    private fun openTable(address: Int) {
        if (tables.size >= MAX_TABLES) throw ZMachineError("Too many nested stream 3 tables")
        tables += MemoryTable(address)
    }

    private fun closeTable() {
        val table = tables.removeLastOrNull() ?: return
        memory.writeWord(table.address, table.count)
    }

    private companion object {
        const val FLAGS2_LOW = 0x11
        const val MAX_TABLES = 16
    }
}
