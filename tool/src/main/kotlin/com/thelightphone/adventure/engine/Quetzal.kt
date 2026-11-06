// Written from the Quetzal 1.4 specification. No code is ported from any interpreter.
package com.thelightphone.adventure.engine

import java.io.ByteArrayOutputStream

/** A saved game after reading, before it is applied to the running machine. */
class SavedGame(
    val memory: ByteArray,
    val pc: Int,
    val frames: List<Frame>,
    val values: List<Int>,
    val atInput: Boolean,
)

/**
 * The Quetzal saved-game format: an IFF file of type IFZS holding IFhd (which story, and the
 * program counter), CMem or UMem (dynamic memory) and Stks (the call stack).
 */
class Quetzal(private val memory: StoryMemory) {
    private val dynamicSize = minOf(memory.staticBase, memory.size)

    /**
     * [pc] is the address of the save instruction's store byte or branch data. With [atInput] it
     * is instead a read instruction to run again, marked by a chunk of our own. Other
     * interpreters do not know that chunk, so files saved that way only load here.
     */
    fun write(stack: CallStack, pc: Int, atInput: Boolean = false): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("IFZS".toByteArray(Charsets.US_ASCII))
        writeChunk(body, "IFhd", storyId() + threeBytes(pc))
        writeChunk(body, "CMem", compress(memory.copyDynamic()))
        writeChunk(body, "Stks", stacks(stack))
        if (atInput) writeChunk(body, AT_INPUT_CHUNK, ByteArray(0))
        val file = ByteArrayOutputStream()
        file.write("FORM".toByteArray(Charsets.US_ASCII))
        file.write(fourBytes(body.size()))
        body.writeTo(file)
        return file.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, id: String, data: ByteArray) {
        out.write(id.toByteArray(Charsets.US_ASCII))
        out.write(fourBytes(data.size))
        out.write(data)
        if (data.size % 2 == 1) out.write(0) // Chunks are padded to an even length.
    }

    /** Release, serial and checksum: the 10 bytes that identify the story. */
    private fun storyId(): ByteArray {
        val addresses = (0x02..0x03) + (0x12..0x17) + (0x1C..0x1D)
        return ByteArray(addresses.size) { memory.originalByte(addresses[it]).toByte() }
    }

    private fun threeBytes(value: Int) =
        byteArrayOf((value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    private fun fourBytes(value: Int) = byteArrayOf((value shr 24).toByte()) + threeBytes(value)

    /** XORs memory with the original. A zero byte then gives the length of a zero run, less 1. */
    private fun compress(current: ByteArray): ByteArray {
        val changes = ByteArray(current.size) {
            (current[it].toInt() xor memory.originalByte(it)).toByte()
        }
        val end = changes.indexOfLast { it.toInt() != 0 } + 1 // Trailing zeros are left out.
        val out = ByteArrayOutputStream()
        var index = 0
        while (index < end) {
            val byte = changes[index++]
            out.write(byte.toInt())
            if (byte.toInt() != 0) continue
            var run = 0
            while (run < 255 && index < end && changes[index].toInt() == 0) {
                run++
                index++
            }
            out.write(run)
        }
        return out.toByteArray()
    }

    private fun stacks(stack: CallStack): ByteArray {
        val out = ByteArrayOutputStream()
        val frames = stack.frameList()
        val values = stack.valueList()
        frames.forEachIndexed { index, frame ->
            val end = frames.getOrNull(index + 1)?.stackBase ?: values.size
            writeFrame(out, frame, values.subList(frame.stackBase, end), isMain = index == 0)
        }
        return out.toByteArray()
    }

    private fun writeFrame(out: ByteArrayOutputStream, frame: Frame, values: List<Int>, isMain: Boolean) {
        out.write(threeBytes(frame.returnPc))
        val discards = frame.storeVariable == null && !isMain
        out.write(frame.locals.size or (if (discards) DISCARD_RESULT else 0))
        out.write(frame.storeVariable ?: 0)
        out.write((1 shl minOf(frame.argCount, 7)) - 1)
        writeWord(out, values.size)
        frame.locals.forEach { writeWord(out, it) }
        values.forEach { writeWord(out, it) }
    }

    private fun writeWord(out: ByteArrayOutputStream, value: Int) {
        out.write(value shr 8)
        out.write(value)
    }

    /** Reads a file. Throws [ZMachineError], and changes nothing, if it cannot be used. */
    fun read(data: ByteArray): SavedGame {
        val chunks = chunks(data)
        val header = chunks["IFhd"] ?: throw ZMachineError("Save file has no IFhd chunk")
        val pc = checkHeader(header)
        val saved = when {
            "CMem" in chunks -> expand(chunks.getValue("CMem"))
            "UMem" in chunks -> uncompressed(chunks.getValue("UMem"))
            else -> throw ZMachineError("Save file has no memory chunk")
        }
        val stacks = chunks["Stks"] ?: throw ZMachineError("Save file has no Stks chunk")
        val values = ArrayList<Int>()
        return SavedGame(saved, pc, readFrames(stacks, values), values, AT_INPUT_CHUNK in chunks)
    }

    /** Splits the file into chunks by id. ANNO, IntD and others are kept but never used. */
    private fun chunks(data: ByteArray): Map<String, ByteArray> {
        if (data.size < 12 || id(data, 0) != "FORM" || id(data, 8) != "IFZS") {
            throw ZMachineError("Not a Quetzal save file")
        }
        val end = minOf(data.size, int(data, 4) + 8)
        val found = HashMap<String, ByteArray>()
        var at = 12
        while (at + 8 <= end) {
            val length = int(data, at + 4)
            if (length < 0 || at + 8 + length > end) throw ZMachineError("Save file is cut short")
            found.getOrPut(id(data, at)) { data.copyOfRange(at + 8, at + 8 + length) }
            at += 8 + length + length % 2
        }
        return found
    }

    private fun id(data: ByteArray, at: Int) = String(data, at, 4, Charsets.ISO_8859_1)

    private fun int(data: ByteArray, at: Int) =
        (0 until 4).fold(0) { value, index -> (value shl 8) or byte(data, at + index) }

    private fun byte(data: ByteArray, at: Int): Int {
        if (at >= data.size) throw ZMachineError("Save file is cut short")
        return data[at].toInt() and 0xFF
    }

    private fun word(data: ByteArray, at: Int) = (byte(data, at) shl 8) or byte(data, at + 1)

    private fun three(data: ByteArray, at: Int) = (word(data, at) shl 8) or byte(data, at + 2)

    /** Checks the save belongs to this story and returns its program counter. */
    private fun checkHeader(chunk: ByteArray): Int {
        if (chunk.size < 13) throw ZMachineError("Save file IFhd chunk is too short")
        if (!chunk.copyOf(10).contentEquals(storyId())) {
            throw ZMachineError("Save file belongs to a different story or release")
        }
        val pc = three(chunk, 10)
        if (pc >= memory.size) throw ZMachineError("Save file has a bad program counter")
        return pc
    }

    private fun originalDynamic() = ByteArray(dynamicSize) { memory.originalByte(it).toByte() }

    private fun expand(chunk: ByteArray): ByteArray {
        val out = originalDynamic()
        var at = 0
        var index = 0
        while (index < chunk.size) {
            val byte = byte(chunk, index++)
            if (byte == 0) {
                at += byte(chunk, index++) + 1
                continue
            }
            if (at >= out.size) throw ZMachineError("Save file memory is too long for this story")
            out[at] = (out[at].toInt() xor byte).toByte()
            at++
        }
        if (at > out.size) throw ZMachineError("Save file memory is too long for this story")
        return out
    }

    private fun uncompressed(chunk: ByteArray): ByteArray {
        if (chunk.size != dynamicSize) throw ZMachineError("Save file memory is the wrong size")
        return chunk
    }

    private fun readFrames(chunk: ByteArray, values: MutableList<Int>): List<Frame> {
        val frames = ArrayList<Frame>()
        var at = 0
        while (at < chunk.size) {
            val flags = byte(chunk, at + 3)
            val localCount = flags and 0x0F
            val valueCount = word(chunk, at + 6)
            val locals = IntArray(localCount) { word(chunk, at + 8 + 2 * it) }
            val store = if (flags and DISCARD_RESULT != 0 || frames.isEmpty()) null else byte(chunk, at + 4)
            val argCount = Integer.numberOfTrailingZeros(byte(chunk, at + 5).inv())
            frames += Frame(three(chunk, at), store, locals, argCount, values.size)
            val firstValue = at + 8 + 2 * localCount
            for (index in 0 until valueCount) values += word(chunk, firstValue + 2 * index)
            at = firstValue + 2 * valueCount
        }
        if (frames.isEmpty()) throw ZMachineError("Save file has an empty call stack")
        return frames
    }

    private companion object {
        const val DISCARD_RESULT = 0x10
        const val AT_INPUT_CHUNK = "LPin"
    }
}
