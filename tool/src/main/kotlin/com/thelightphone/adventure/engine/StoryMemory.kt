// Written from the Z-Machine Standards Document 1.1. No code is ported from JSZM or ifvms.js.
package com.thelightphone.adventure.engine

/** A broken or unsupported story file, or an illegal operation by the story. */
class ZMachineError(message: String) : RuntimeException(message)

/**
 * The story's memory (Standard section 1). Reads reach all of it; writes reach dynamic memory
 * only. Addresses are plain unsigned byte addresses.
 */
class StoryMemory(story: ByteArray) {
    private val bytes = story.copyOf()
    private val original = story.copyOf()

    init {
        if (bytes.size < HEADER_SIZE) throw ZMachineError("Story file is too short to have a header")
    }

    val size: Int get() = bytes.size

    /** Dynamic memory ends here (header word at 0x0E). */
    val staticBase: Int = readWord(0x0E)

    fun readByte(address: Int): Int {
        check(address)
        return bytes[address].toInt() and 0xFF
    }

    fun readWord(address: Int): Int = (readByte(address) shl 8) or readByte(address + 1)

    fun writeByte(address: Int, value: Int) {
        check(address)
        if (address >= staticBase) {
            throw ZMachineError("Write to read-only memory at 0x${address.toString(16)}")
        }
        bytes[address] = value.toByte()
    }

    fun writeWord(address: Int, value: Int) {
        writeByte(address, value shr 8)
        writeByte(address + 1, value)
    }

    /** A byte as it was in the file, whatever the game has changed since. */
    fun originalByte(address: Int): Int {
        check(address)
        return original[address].toInt() and 0xFF
    }

    /** A copy of dynamic memory, for undo. */
    fun copyDynamic(): ByteArray = bytes.copyOf(dynamicSize)

    /** Puts back a copy made by [copyDynamic]. */
    fun restoreDynamic(saved: ByteArray) {
        saved.copyInto(bytes, endIndex = dynamicSize)
    }

    /** Returns dynamic memory to how the file had it, for `restart`. */
    fun resetDynamic() = restoreDynamic(original)

    private val dynamicSize: Int get() = minOf(staticBase, bytes.size)

    private fun check(address: Int) {
        if (address < 0 || address >= bytes.size) {
            throw ZMachineError("Address 0x${address.toString(16)} is outside the story")
        }
    }

    companion object {
        const val HEADER_SIZE = 64
    }
}
