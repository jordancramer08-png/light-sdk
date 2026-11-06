package com.thelightphone.adventure.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tiny hand-assembled programs. Code starts at CODE; global 0 is variable 0x10. */
class OpcodeTest {
    private val quit = 0xBA

    private fun story(version: Int, code: List<Int>, setup: (ByteArray) -> Unit): ByteArray {
        val bytes = ByteArray(0x800)
        bytes[0] = version.toByte()
        putWord(bytes, 0x06, CODE)
        putWord(bytes, 0x08, 0x700) // Empty dictionary.
        putWord(bytes, 0x0A, 0x40)
        putWord(bytes, 0x0C, GLOBALS)
        putWord(bytes, 0x0E, 0x800)
        code.forEachIndexed { index, value -> bytes[CODE + index] = value.toByte() }
        setup(bytes)
        return bytes
    }

    private fun putWord(bytes: ByteArray, address: Int, value: Int) {
        bytes[address] = (value shr 8).toByte()
        bytes[address + 1] = value.toByte()
    }

    private fun run(
        version: Int,
        code: List<Int>,
        io: RecordingIo = RecordingIo(),
        seed: Long? = null,
        setup: (ByteArray) -> Unit = {},
    ): ZMachine {
        val machine = ZMachine(Story(story(version, code, setup)), io, seed)
        machine.run()
        return machine
    }

    private fun ZMachine.global(index: Int) = memory.readWord(GLOBALS + 2 * index)

    @Test
    fun arithmeticIsSigned() {
        // sub 3 5 -> g0; div g0 2 -> g1
        val machine = run(3, listOf(0x15, 3, 5, 0x10, 0x57, 0x10, 2, 0x11, quit))
        assertEquals(0xFFFE, machine.global(0))
        assertEquals(0xFFFF, machine.global(1))
    }

    @Test
    fun unknownOpcodeIsLoggedAndTheGameContinues() {
        val io = RecordingIo()
        // Illegal 2OP 0, then add 1 1 -> g0.
        val machine = run(5, listOf(0x00, 1, 2, 0x14, 1, 1, 0x10, quit), io)
        assertTrue(io.logs.single().startsWith("unimplemented opcode"), io.logs.toString())
        assertEquals(2, machine.global(0))
    }

    @Test
    fun divisionByZeroDoesNotStopTheGame() {
        val io = RecordingIo()
        val machine = run(5, listOf(0x17, 1, 0, 0x10, 0x14, 1, 1, 0x11, quit), io)
        assertEquals(1, io.logs.size)
        assertEquals(2, machine.global(1))
    }

    @Test
    fun runningOffTheEndHaltsInsteadOfCrashing() {
        val io = RecordingIo()
        // Fill the rest of the story with illegal opcodes, so the program counter walks off it.
        val machine = run(5, listOf(0x00, 0, 0), io)
        assertTrue(machine.halted)
        assertTrue(io.logs.last().startsWith("cannot read instruction"), io.logs.last())
    }

    @Test
    fun stream3CapturesTextInsteadOfPrinting() {
        val io = RecordingIo()
        val hi = 0x8000 or (13 shl 10) or (14 shl 5) or 5
        val code = listOf(
            0xF3, 0x4F, 3, 0x06, 0x00, // output_stream 3 0x600
            0xB2, hi shr 8, hi and 0xFF, // print "hi"
            0xF3, 0x3F, 0xFF, 0xFD, // output_stream -3
            quit,
        )
        val machine = run(5, code, io)
        assertEquals("", io.output.toString())
        assertEquals(2, machine.memory.readWord(0x600))
        assertEquals('h'.code, machine.memory.readByte(0x602))
        assertEquals('i'.code, machine.memory.readByte(0x603))
    }

    @Test
    fun negativeRandomSeedRepeatsTheSequence() {
        // random -5 -> g1; random 1000 -> g0
        val code = listOf(0xE7, 0x3F, 0xFF, 0xFB, 0x11, 0xE7, 0x3F, 0x03, 0xE8, 0x10, quit)
        assertEquals(run(5, code).global(0), run(5, code).global(0))
    }

    @Test
    fun constructorSeedRepeatsTheSequence() {
        val code = listOf(0xE7, 0x3F, 0x03, 0xE8, 0x10, quit)
        assertEquals(run(5, code, seed = 7).global(0), run(5, code, seed = 7).global(0))
    }

    @Test
    fun restoreUndoRewindsMemoryAndReturnsTwo() {
        val code = listOf(
            0xBE, 0x09, 0xFF, 0x10, // save_undo -> g0
            0x41, 0x10, 2, 0xC8, // je g0 2 ?quit
            0x95, 0x11, // inc g1
            0xBE, 0x0A, 0xFF, 0x12, // restore_undo -> g2
            quit,
        )
        val machine = run(5, code)
        assertEquals(2, machine.global(0))
        assertEquals(0, machine.global(1))
    }

    @Test
    fun scanTableFindsAWord() {
        // scan_table 0x1234 0x600 3 -> g0, branching to the next instruction.
        val code = listOf(0xF7, 0x03, 0x12, 0x34, 0x06, 0x00, 0x00, 3, 0x10, 0xC2, quit)
        val machine = run(5, code) { putWord(it, 0x604, 0x1234) }
        assertEquals(0x604, machine.global(0))
    }

    @Test
    fun copyTableHandlesOverlap() {
        // copy_table 0x600 0x601 3
        val code = listOf(0xFD, 0x03, 0x06, 0x00, 0x06, 0x01, 0x00, 3, quit)
        val machine = run(5, code) {
            it[0x600] = 1
            it[0x601] = 2
            it[0x602] = 3
        }
        assertEquals(listOf(1, 1, 2, 3), (0x600..0x603).map(machine.memory::readByte))
    }

    @Test
    fun objectZeroMeansNothingAndIsNotedAsUnusual() {
        val io = RecordingIo()
        // test_attr 0 1 ?~next: false, so the branch-on-false is taken and skips "store g0 9".
        val code = listOf(0x0A, 0, 1, 0x45, 0x0D, 0x10, 9, quit)
        val machine = run(5, code, io)
        assertEquals(emptyList(), io.failures)
        assertTrue(io.logs.single().startsWith("unusual: object 0"), io.logs.toString())
        assertEquals(0, machine.global(0))
    }

    @Test
    fun checkUnicodeReportsPrintableAndTypeable() {
        // check_unicode 0xE4 (a with umlaut) -> g0
        val machine = run(5, listOf(0xBE, 0x0C, 0x3F, 0x00, 0xE4, 0x10, quit))
        assertEquals(3, machine.global(0))
    }

    @Test
    fun saveAsksForASlotAndReportsSuccess() {
        val io = RecordingIo()
        // Version 3 save branches over "store g0 9" when it succeeds.
        val machine = run(3, listOf(0xB5, 0xC5, 0x0D, 0x10, 9, quit), io)
        assertEquals(1, io.slotRequests)
        assertEquals(0, machine.global(0))
    }

    @Test
    fun encodeTextMakesADictionaryKey() {
        // encode_text 0x600 3 0 0x610
        val machine = run(5, listOf(0xFC, 0x14, 0x06, 0x00, 3, 0, 0x06, 0x10, quit)) {
            "abc".forEachIndexed { index, char -> it[0x600 + index] = char.code.toByte() }
        }
        assertEquals((6 shl 10) or (7 shl 5) or 8, machine.memory.readWord(0x610))
        assertEquals((5 shl 10) or (5 shl 5) or 5, machine.memory.readWord(0x612))
        assertEquals(0x8000 or (5 shl 10) or (5 shl 5) or 5, machine.memory.readWord(0x614))
    }

    @Test
    fun getCursorWritesLineAndColumn() {
        val machine = run(5, listOf(0xF0, 0x3F, 0x06, 0x00, quit))
        assertEquals(1, machine.memory.readWord(0x600))
        assertEquals(1, machine.memory.readWord(0x602))
    }

    @Test
    fun printTablePrintsRows() {
        val io = RecordingIo()
        // print_table 0x600 2 2
        run(5, listOf(0xFE, 0x17, 0x06, 0x00, 2, 2, quit), io) {
            "abcd".forEachIndexed { index, char -> it[0x600 + index] = char.code.toByte() }
        }
        assertEquals("ab\ncd", io.output.toString())
    }

    @Test
    fun eraseLineOnlyActsOnOne() {
        val io = RecordingIo()
        run(5, listOf(0xEE, 0x7F, 1, 0xEE, 0x7F, 2, quit), io)
        assertEquals(1, io.eraseLineCalls)
    }

    private companion object {
        const val CODE = 0x400
        const val GLOBALS = 0x200
    }
}
