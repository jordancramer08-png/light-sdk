package com.thelightphone.adventure.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Small hand-built stories, for rules the real games may not exercise. */
class SyntheticStoryTest {
    private fun blank(version: Int, size: Int = 2048): ByteArray {
        val bytes = ByteArray(size)
        bytes[0] = version.toByte()
        putWord(bytes, 0x0E, size) // All memory is dynamic.
        return bytes
    }

    private fun putWord(bytes: ByteArray, address: Int, value: Int) {
        bytes[address] = (value shr 8).toByte()
        bytes[address + 1] = value.toByte()
    }

    private fun zword(a: Int, b: Int, c: Int, last: Boolean = false) =
        (if (last) 0x8000 else 0) or (a shl 10) or (b shl 5) or c

    private fun parts(bytes: ByteArray): Triple<StoryMemory, StoryHeader, ZText> {
        val memory = StoryMemory(bytes)
        val header = StoryHeader(memory)
        return Triple(memory, header, ZText(memory, header))
    }

    @Test
    fun packedAddressesFollowTheVersion() {
        assertEquals(0x200, parts(blank(3)).second.unpack(0x100))
        assertEquals(0x400, parts(blank(4)).second.unpack(0x100))
        assertEquals(0x400, parts(blank(5)).second.unpack(0x100))
        assertEquals(0x800, parts(blank(8)).second.unpack(0x100))
        assertEquals(0xFFFF * 8, parts(blank(8)).second.unpack(0xFFFF))
    }

    @Test
    fun fileLengthScaleFollowsTheVersion() {
        for ((version, scale) in listOf(3 to 2, 4 to 4, 5 to 4, 8 to 8)) {
            val bytes = blank(version)
            putWord(bytes, 0x1A, 100)
            assertEquals(100 * scale, parts(bytes).second.fileLength)
        }
    }

    @Test
    fun unsupportedVersionsAreRefused() {
        for (version in listOf(0, 1, 2, 6, 7, 9)) {
            assertFailsWith<ZMachineError> { StoryHeader(StoryMemory(blank(version))) }
        }
    }

    @Test
    fun writesToStaticMemoryAreRefused() {
        val bytes = blank(3)
        putWord(bytes, 0x0E, 0x100)
        val memory = StoryMemory(bytes)
        memory.writeByte(0xFF, 1)
        assertFailsWith<ZMachineError> { memory.writeByte(0x100, 1) }
        assertFailsWith<ZMachineError> { memory.readByte(bytes.size) }
    }

    @Test
    fun decodesShiftsNewlineAndZsciiEscape() {
        val bytes = blank(3)
        // "a", shift to A1 "B", shift to A2 "0", new line, then ZSCII 65 by escape: "aB0\nA"
        putWord(bytes, 0x100, zword(6, 4, 7))
        putWord(bytes, 0x102, zword(5, 8, 5))
        putWord(bytes, 0x104, zword(7, 5, 6))
        putWord(bytes, 0x106, zword(2, 1, 5, last = true))
        val decoded = parts(bytes).third.decode(0x100)
        assertEquals("aB0\nA", decoded.text)
        assertEquals(0x108, decoded.nextAddress)
    }

    @Test
    fun shiftLastsForOneCharacterOnly() {
        val bytes = blank(3)
        putWord(bytes, 0x100, zword(4, 6, 6, last = true))
        assertEquals("Aa", parts(bytes).third.decode(0x100).text)
    }

    @Test
    fun expandsAbbreviationsFromAllThreeBanks() {
        val bytes = blank(3)
        putWord(bytes, 0x18, 0x200) // Abbreviation table.
        putWord(bytes, 0x200 + 2 * 33, 0x300 / 2) // Bank 2, entry 1 is number 33.
        putWord(bytes, 0x300, zword(7, 8, 9, last = true)) // "bcd"
        putWord(bytes, 0x100, zword(2, 1, 6, last = true))
        assertEquals("bcda", parts(bytes).third.decode(0x100).text)
    }

    @Test
    fun abbreviationInsideAbbreviationIsRefused() {
        val bytes = blank(3)
        putWord(bytes, 0x18, 0x200)
        putWord(bytes, 0x200, 0x300 / 2)
        putWord(bytes, 0x300, zword(1, 0, 5, last = true))
        putWord(bytes, 0x100, zword(1, 0, 5, last = true))
        assertFailsWith<ZMachineError> { parts(bytes).third.decode(0x100) }
    }

    @Test
    fun customAlphabetTableReplacesTheDefault() {
        val bytes = blank(5)
        putWord(bytes, 0x34, 0x400)
        for (i in 0 until 78) bytes[0x400 + i] = 'x'.code.toByte()
        bytes[0x400] = 'q'.code.toByte() // A0 first letter
        bytes[0x400 + 26] = 'R'.code.toByte() // A1 first letter
        bytes[0x400 + 54] = '9'.code.toByte() // A2 Z-character 8
        putWord(bytes, 0x100, zword(6, 4, 6))
        putWord(bytes, 0x102, zword(5, 8, 5))
        putWord(bytes, 0x104, zword(7, 5, 5, last = true))
        val text = parts(bytes).third
        assertEquals("qR9\n", text.decode(0x100).text)
        assertEquals(6, text.encodeDictionaryWord("q")[0] shr 10)
    }

    @Test
    fun defaultExtraCharactersMatchTheStandard() {
        val text = parts(blank(5)).third
        assertEquals('ä'.code, text.zsciiToUnicode(155))
        assertEquals('¿'.code, text.zsciiToUnicode(223))
        assertEquals(null, text.zsciiToUnicode(224))
        assertEquals(null, text.zsciiToUnicode(0))
        assertEquals(161, text.unicodeToZscii('ß'.code))
    }

    @Test
    fun unicodeTableFromTheHeaderExtensionIsUsed() {
        val bytes = blank(5)
        putWord(bytes, 0x36, 0x500)
        putWord(bytes, 0x500, 3)
        putWord(bytes, 0x506, 0x520)
        bytes[0x520] = 1
        putWord(bytes, 0x521, 0x20AC) // Euro sign
        val text = parts(bytes).third
        assertEquals(0x20AC, text.zsciiToUnicode(155))
        assertEquals(null, text.zsciiToUnicode(156))
    }

    @Test
    fun encodesToTheVersionKeyLength() {
        val v3 = parts(blank(3)).third.encodeDictionaryWord("a")
        assertEquals(listOf(zword(6, 5, 5), zword(5, 5, 5, last = true)), v3.toList())
        val v5 = parts(blank(5)).third.encodeDictionaryWord("abcdefghijkl")
        assertEquals(3, v5.size)
        assertEquals(zword(12, 13, 14, last = true), v5[2])
    }

    @Test
    fun encodesPunctuationAndUnknownCharacters() {
        val text = parts(blank(5)).third
        assertEquals(zword(5, 18, 5), text.encodeDictionaryWord(".")[0]) // A2 position 12 + 6
        assertEquals(zword(5, 6, 4), text.encodeDictionaryWord("ä")[0]) // Escape, 155 = 4,27
        assertEquals(zword(27, 5, 5), text.encodeDictionaryWord("ä")[1])
    }

    private fun smallObjects(): ObjectTable {
        val bytes = blank(3)
        putWord(bytes, 0x0A, 0x100)
        putWord(bytes, 0x100 + 2 * 4, 77) // Default for property 5.
        val entries = 0x100 + 62
        // Object 1 holds 2, then 3.
        bytes[entries + 6] = 2
        putWord(bytes, entries + 7, 0x200)
        bytes[entries + 9 + 4] = 1
        bytes[entries + 9 + 5] = 3
        putWord(bytes, entries + 9 + 7, 0x210)
        bytes[entries + 18 + 4] = 1
        putWord(bytes, entries + 18 + 7, 0x220)
        bytes[entries] = 0x80.toByte() // Attribute 0 on object 1.
        // Object 1: no name; property 9 has 2 bytes, property 4 has 1, property 2 has 4.
        bytes[0x201] = ((1 shl 5) or 9).toByte()
        putWord(bytes, 0x202, 0x1234)
        bytes[0x204] = 4
        bytes[0x205] = 0x56
        bytes[0x206] = ((3 shl 5) or 2).toByte()
        val (memory, header, text) = parts(bytes)
        return ObjectTable(memory, header, text)
    }

    @Test
    fun version3ObjectsReadAndChange() {
        val objects = smallObjects()
        assertEquals(3, objects.objectCount.coerceAtMost(3))
        assertEquals(2, objects.child(1))
        assertEquals(3, objects.sibling(2))
        assertEquals(1, objects.parent(3))

        assertTrue(objects.hasAttribute(1, 0))
        assertFalse(objects.hasAttribute(1, 1))
        objects.setAttribute(1, 31)
        assertTrue(objects.hasAttribute(1, 31))
        objects.clearAttribute(1, 0)
        assertFalse(objects.hasAttribute(1, 0))
        assertFailsWith<ZMachineError> { objects.hasAttribute(1, 32) }

        objects.remove(3)
        assertEquals(0, objects.parent(3))
        assertEquals(0, objects.sibling(2))
        objects.insert(3, 1)
        assertEquals(3, objects.child(1))
        assertEquals(2, objects.sibling(3))
    }

    @Test
    fun version3PropertiesReadAndChange() {
        val objects = smallObjects()
        assertEquals(0x1234, objects.getProperty(1, 9))
        assertEquals(0x56, objects.getProperty(1, 4))
        assertEquals(77, objects.getProperty(1, 5))
        assertEquals(9, objects.nextProperty(1, 0))
        assertEquals(4, objects.nextProperty(1, 9))
        assertEquals(2, objects.nextProperty(1, 4))
        assertEquals(0, objects.nextProperty(1, 2))
        assertEquals(2, objects.propertyLength(objects.propertyAddress(1, 9)))
        assertEquals(4, objects.propertyLength(objects.propertyAddress(1, 2)))
        assertEquals(0, objects.propertyAddress(1, 5))
        assertEquals(0, objects.propertyLength(0))
        objects.putProperty(1, 4, 0x99)
        assertEquals(0x99, objects.getProperty(1, 4))
        assertFailsWith<ZMachineError> { objects.putProperty(1, 2, 1) }
        assertFailsWith<ZMachineError> { objects.putProperty(1, 5, 1) }
    }

    @Test
    fun version5ObjectsUseWideEntriesAndLongProperties() {
        val bytes = blank(5)
        putWord(bytes, 0x0A, 0x100)
        putWord(bytes, 0x100 + 2 * 62, 88) // Default for property 63.
        val entry = 0x100 + 126
        bytes[entry + 5] = 1 // Attribute 47.
        putWord(bytes, entry + 6, 300)
        putWord(bytes, entry + 8, 301)
        putWord(bytes, entry + 10, 302)
        putWord(bytes, entry + 12, 0x300)
        bytes[0x301] = 0x41 // Property 1, two bytes (bit 6).
        putWord(bytes, 0x302, 0xBEEF)
        bytes[0x304] = 0x02 // Property 2, one byte.
        bytes[0x305] = 7
        bytes[0x306] = (0x80 or 3).toByte() // Property 3, long form.
        bytes[0x307] = 0x80.toByte() // Size 0 means 64.
        val (memory, header, text) = parts(bytes)
        val objects = ObjectTable(memory, header, text)

        assertTrue(objects.hasAttribute(1, 47))
        assertFailsWith<ZMachineError> { objects.hasAttribute(1, 48) }
        assertEquals(300, objects.parent(1))
        assertEquals(301, objects.sibling(1))
        assertEquals(302, objects.child(1))
        assertEquals(0xBEEF, objects.getProperty(1, 1))
        assertEquals(7, objects.getProperty(1, 2))
        assertEquals(88, objects.getProperty(1, 63))
        assertEquals(2, objects.propertyLength(objects.propertyAddress(1, 1)))
        assertEquals(1, objects.propertyLength(objects.propertyAddress(1, 2)))
        assertEquals(64, objects.propertyLength(objects.propertyAddress(1, 3)))
    }

    @Test
    fun unsortedDictionaryIsSearchedInFull() {
        val bytes = blank(3)
        val base = 0x300
        bytes[base] = 1
        bytes[base + 1] = ','.code.toByte()
        bytes[base + 2] = 7
        putWord(bytes, base + 3, -2 and 0xFFFF)
        val (memory, header, text) = parts(bytes)
        // Out of order on purpose.
        val zebra = text.encodeDictionaryWord("zebra")
        val apple = text.encodeDictionaryWord("apple")
        val writable = StoryMemory(bytes.also {
            putWord(it, base + 5, zebra[0]); putWord(it, base + 7, zebra[1])
            putWord(it, base + 12, apple[0]); putWord(it, base + 14, apple[1])
        })
        val dictionary = Dictionary(writable, ZText(writable, StoryHeader(writable)), base)
        assertEquals(2, dictionary.size)
        assertEquals(base + 12, dictionary.lookup("apple"))
        assertEquals(base + 5, dictionary.lookup("ZEBRA"))
        assertEquals(0, dictionary.lookup("mango"))
        assertEquals(listOf("apple", ",", "zebra"), dictionary.tokenize("apple, zebra").map { it.text })
        assertEquals(listOf(0, 5, 7), dictionary.tokenize("apple, zebra").map { it.start })
        memory.readByte(0)
        header.version
    }

    @Test
    fun zIoCanBeImplementedWithoutAnyScreen() {
        val printed = StringBuilder()
        val io = object : ZIo {
            override fun print(text: String) { printed.append(text) }
            override fun readLine(maxLength: Int, timeoutTenths: Int) = "look".take(maxLength)
            override fun readChar(timeoutTenths: Int): Int? = null
            override fun showStatusLine(status: ZStatusLine) = Unit
            override fun splitWindow(lines: Int) = Unit
            override fun setWindow(window: Int) = Unit
            override fun eraseWindow(window: Int) = Unit
            override fun setCursor(line: Int, column: Int) = Unit
            override fun setTextStyle(style: Int) = Unit
            override fun setColors(foreground: ZColor, background: ZColor) = Unit
            override fun save(data: ByteArray) = false
            override fun restore(): ByteArray? = null
        }
        io.print("Hello")
        assertEquals("Hello", printed.toString())
        assertEquals("lo", io.readLine(2))
    }
}
