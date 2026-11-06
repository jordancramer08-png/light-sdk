// Written from the Z-Machine Standards Document 1.1. No code is ported from JSZM or ifvms.js.
package com.thelightphone.adventure.engine

/** The 64-byte header (Standard section 11), for versions 3, 4, 5 and 8. */
class StoryHeader(private val memory: StoryMemory) {
    val version: Int = memory.readByte(0x00)

    init {
        if (version !in SUPPORTED_VERSIONS) {
            throw ZMachineError("Z-machine version $version is not supported (only 3, 4, 5 and 8)")
        }
    }

    val flags1: Int get() = memory.readByte(0x01)
    val release: Int = memory.readWord(0x02)
    val highMemoryBase: Int = memory.readWord(0x04)
    val initialPc: Int = memory.readWord(0x06)
    val dictionary: Int = memory.readWord(0x08)
    val objectTable: Int = memory.readWord(0x0A)
    val globals: Int = memory.readWord(0x0C)
    val staticBase: Int = memory.readWord(0x0E)
    val flags2: Int get() = memory.readWord(0x10)
    val serial: String = (0x12..0x17).map { memory.readByte(it).toChar() }.joinToString("")
    val abbreviations: Int = memory.readWord(0x18)
    val checksum: Int = memory.readWord(0x1C)

    /** Length in bytes. The header stores it divided by a version-specific scale. */
    val fileLength: Int = memory.readWord(0x1A) * when (version) {
        3 -> 2
        4, 5 -> 4
        else -> 8
    }

    /** Version 5+ only; 0 means none. */
    val terminatingChars: Int = if (version >= 5) memory.readWord(0x2E) else 0
    val alphabetTable: Int = if (version >= 5) memory.readWord(0x34) else 0
    val extensionTable: Int = if (version >= 5) memory.readWord(0x36) else 0

    /** Unicode translation table from header extension word 3; 0 means the default table. */
    val unicodeTable: Int
        get() = if (extensionTable != 0 && memory.readWord(extensionTable) >= 3) {
            memory.readWord(extensionTable + 6)
        } else {
            0
        }

    /** Version 3 only: true when the status line shows a time instead of score and moves. */
    val statusShowsTime: Boolean get() = version == 3 && flags1 and 0x02 != 0

    /** Byte address of a packed routine or string. Offsets apply only to versions 6 and 7. */
    fun unpack(packed: Int): Int = packed * when (version) {
        3 -> 2
        4, 5 -> 4
        else -> 8
    }

    /** Sum of the file's bytes after the header, as the `verify` opcode computes it. */
    fun computeChecksum(): Int {
        val end = if (fileLength == 0) memory.size else minOf(fileLength, memory.size)
        var sum = 0
        for (address in StoryMemory.HEADER_SIZE until end) sum += memory.originalByte(address)
        return sum and 0xFFFF
    }

    companion object {
        val SUPPORTED_VERSIONS = setOf(3, 4, 5, 8)
    }
}
