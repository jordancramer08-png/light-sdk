// Written from the Z-Machine Standards Document 1.1. No code is ported from JSZM or ifvms.js.
package com.thelightphone.adventure.engine

/** Moves typed text in and out of the story's text and parse buffers (Standard section 15). */
internal class LineInput(
    private val memory: StoryMemory,
    private val text: ZText,
    private val version: Int,
    private val defaultDictionary: Dictionary,
) {
    private val dictionaries = HashMap<Int, Dictionary>()

    /** Versions 3 and 4 keep text from byte 1; versions 5+ from byte 2, after a count. */
    private val textOffset = if (version >= 5) 2 else 1

    /** How many more characters the buffer can take. */
    fun roomLeft(buffer: Int): Int = maxLength(buffer) - presetText(buffer).length

    private fun maxLength(buffer: Int): Int {
        val size = memory.readByte(buffer)
        return if (version >= 5) size else size - 1
    }

    /** Version 5+ games can leave text in the buffer for the player to carry on from. */
    private fun presetText(buffer: Int): String = if (version >= 5) bufferText(buffer) else ""

    /** Stores a typed line after any preset text, in lower case. */
    fun store(buffer: Int, line: String) {
        val all = (presetText(buffer) + line).lowercase().take(maxLength(buffer))
        all.forEachIndexed { index, char ->
            memory.writeByte(buffer + textOffset + index, text.unicodeToZscii(char.code))
        }
        if (version >= 5) {
            memory.writeByte(buffer + 1, all.length)
        } else {
            memory.writeByte(buffer + textOffset + all.length, 0)
        }
    }

    private fun bufferText(buffer: Int): String {
        val out = StringBuilder()
        val length = if (version >= 5) memory.readByte(buffer + 1) else Int.MAX_VALUE
        var index = 0
        while (index < length) {
            val zscii = memory.readByte(buffer + textOffset + index++)
            if (version < 5 && zscii == 0) break
            out.appendCodePoint(text.zsciiToUnicode(zscii) ?: '?'.code)
        }
        return out.toString()
    }

    /** The tokenise opcode. With [skipUnknown], unknown words leave their parse slot alone. */
    fun tokenise(buffer: Int, parse: Int, dictionaryAddress: Int = 0, skipUnknown: Boolean = false) {
        val tokens = dictionaryFor(dictionaryAddress).tokenize(bufferText(buffer))
            .take(memory.readByte(parse))
        memory.writeByte(parse + 1, tokens.size)
        tokens.forEachIndexed { index, token ->
            if (token.entryAddress != 0 || !skipUnknown) writeToken(parse + 2 + 4 * index, token)
        }
    }

    private fun writeToken(address: Int, token: Token) {
        memory.writeWord(address, token.entryAddress)
        memory.writeByte(address + 2, token.text.length)
        memory.writeByte(address + 3, token.start + textOffset)
    }

    private fun dictionaryFor(address: Int): Dictionary {
        if (address == 0) return defaultDictionary
        return dictionaries.getOrPut(address) { Dictionary(memory, text, address) }
    }

    /** The encode_text opcode: encodes part of a text buffer as a dictionary key. */
    fun encode(source: Int, length: Int, from: Int, destination: Int) {
        val word = StringBuilder()
        for (index in 0 until length) {
            word.appendCodePoint(text.zsciiToUnicode(memory.readByte(source + from + index)) ?: '?'.code)
        }
        text.encodeDictionaryWord(word.toString()).forEachIndexed { index, value ->
            memory.writeWord(destination + 2 * index, value)
        }
    }
}
