package com.thelightphone.adventure.engine

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuetzalTest {
    private val zork = File("src/main/assets/zork1.dat")

    private fun bareStory(): ByteArray {
        val bytes = ByteArray(0x200)
        bytes[0] = 5
        bytes[0x03] = 7 // Release.
        "123456".forEachIndexed { index, char -> bytes[0x12 + index] = char.code.toByte() }
        bytes[0x0E] = 0x01 // Dynamic memory ends at 0x100.
        bytes[0x40] = 0x11
        return bytes
    }

    private fun chunk(id: String, vararg data: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray())
        out.write(byteArrayOf(0, 0, 0, data.size.toByte()))
        data.forEach(out::write)
        if (data.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun form(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold("IFZS".toByteArray()) { all, next -> all + next }
        return "FORM".toByteArray() + byteArrayOf(0, 0, (body.size shr 8).toByte(), body.size.toByte()) + body
    }

    private val ifhd = chunk(
        "IFhd", 0, 7, '1'.code, '2'.code, '3'.code, '4'.code, '5'.code, '6'.code, 0, 0, 0, 0x01, 0x23,
    )

    // A dummy main frame holding one stack value, then a routine with 2 locals storing to 0x10.
    private val stks = chunk(
        "Stks",
        0, 0, 0, 0, 0, 0, 0, 1, 0xAB, 0xCD,
        0, 0x01, 0x50, 2, 0x10, 1, 0, 0, 0, 5, 0, 6,
    )

    @Test
    fun readsAHandBuiltFileLikeAnotherInterpreterWrites() {
        // CMem: skip 0x40 bytes, then flip 0x11 to 0x22 by XOR with 0x33. An ANNO chunk is ignored.
        val file = form(ifhd, chunk("ANNO", 'h'.code), chunk("CMem", 0, 0x3F, 0x33), stks)
        val saved = Quetzal(StoryMemory(bareStory())).read(file)
        assertEquals(0x22, saved.memory[0x40].toInt())
        assertEquals(0x123, saved.pc)
        assertEquals(listOf(0xABCD), saved.values)
        assertEquals(2, saved.frames.size)
        assertEquals(0x150, saved.frames[1].returnPc)
        assertEquals(0x10, saved.frames[1].storeVariable)
        assertContentEquals(intArrayOf(5, 6), saved.frames[1].locals)
        assertEquals(1, saved.frames[1].argCount)
        assertEquals(1, saved.frames[1].stackBase)
    }

    @Test
    fun readsUncompressedMemory() {
        val memory = IntArray(0x100) { 0x5A }
        val saved = Quetzal(StoryMemory(bareStory())).read(form(ifhd, chunk256("UMem", memory), stks))
        assertEquals(0x5A, saved.memory[0x80].toInt())
    }

    private fun chunk256(id: String, data: IntArray): ByteArray =
        id.toByteArray() + byteArrayOf(0, 0, 1, 0) + ByteArray(data.size) { data[it].toByte() }

    @Test
    fun refusesOtherStoriesAndDamagedFiles() {
        val quetzal = Quetzal(StoryMemory(bareStory()))
        val wrongStory = chunk("IFhd", 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0)
        assertFailsWith<ZMachineError> { quetzal.read(form(wrongStory, chunk("CMem"), stks)) }
        assertFailsWith<ZMachineError> { quetzal.read("not a save".toByteArray()) }
        assertFailsWith<ZMachineError> { quetzal.read(form(ifhd, stks)) }
        val tooLong = chunk("CMem", 0, 0xFF, 0x33)
        assertFailsWith<ZMachineError> { quetzal.read(form(ifhd, tooLong, stks)) }
        val good = form(ifhd, chunk("CMem"), stks)
        assertFailsWith<ZMachineError> { quetzal.read(good.copyOf(good.size - 6)) }
    }

    private fun play(io: RecordingIo, machine: ZMachine = ZMachine(Story(zork.readBytes()), io, 42)) {
        try {
            machine.run()
        } catch (finished: ScriptFinished) {
            // The commands ran out.
        }
    }

    @Test
    fun zorkSaveThenRestoreInAFreshGame() {
        val first = RecordingIo(listOf("open mailbox", "take leaflet", "save", "drop leaflet"))
        play(first)
        val data = first.saves.getValue("slot")

        val second = RecordingIo(listOf("restore", "inventory"))
        second.saves["slot"] = data
        play(second)
        val text = second.output.toString()
        assertEquals(emptyList(), second.failures)
        assertTrue("Ok." in text, text)
        assertTrue("A leaflet" in text, text)
        assertEquals(3, second.statusLines.last().second) // 2 moves saved, then inventory.
    }

    @Test
    fun aSaveFromAnotherStoryIsRefusedAndTheGameCarriesOn() {
        val other = RecordingIo(listOf("save"))
        play(other, ZMachine(Story(File("src/main/assets/zork2.dat").readBytes()), other, 42))
        val io = RecordingIo(listOf("restore", "inventory"))
        io.saves["slot"] = other.saves.getValue("slot")
        play(io)
        assertTrue("Failed." in io.output.toString(), io.output.toString())
        assertTrue(io.logs.any { "different story" in it }, io.logs.toString())
        assertFalse(io.failures.isNotEmpty())
    }

    @Test
    fun zorkRestartGoesBackToTheBeginning() {
        val io = RecordingIo(listOf("open mailbox", "take leaflet", "restart", "y", "inventory"))
        play(io)
        assertTrue("You are empty-handed." in io.output.toString(), io.output.toString())
    }
}
