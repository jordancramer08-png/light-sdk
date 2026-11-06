// Written from the Z-Machine Standards Document 1.1. No code is ported from JSZM or ifvms.js.
package com.thelightphone.adventure.engine

/** A decoded string and the address of the first byte after it. */
data class DecodedText(val text: String, val nextAddress: Int)

/** Z-character text and ZSCII (Standard section 3). */
class ZText(private val memory: StoryMemory, private val header: StoryHeader) {
    private val alphabets: IntArray = loadAlphabets()
    private val extraChars: IntArray = loadExtraChars()

    /** Dictionary keys hold 6 Z-characters in version 3 and 9 in versions 4+. */
    val dictionaryZChars: Int = if (header.version == 3) 6 else 9

    fun decode(address: Int): DecodedText {
        val zchars = ArrayList<Int>()
        var next = address
        while (true) {
            val word = memory.readWord(next)
            next += 2
            zchars += (word shr 10) and 31
            zchars += (word shr 5) and 31
            zchars += word and 31
            if (word and 0x8000 != 0) break
        }
        return DecodedText(decodeZChars(zchars, allowAbbreviations = true), next)
    }

    private fun decodeZChars(zchars: List<Int>, allowAbbreviations: Boolean): String {
        val out = StringBuilder()
        var alphabet = 0
        var i = 0
        while (i < zchars.size) {
            val z = zchars[i++]
            when {
                z == 0 -> out.append(' ')
                z in 1..3 -> {
                    if (!allowAbbreviations) throw ZMachineError("Abbreviation inside an abbreviation")
                    if (i >= zchars.size) break
                    out.append(abbreviation(32 * (z - 1) + zchars[i++]))
                }
                z == 4 -> { alphabet = 1; continue }
                z == 5 -> { alphabet = 2; continue }
                alphabet == 2 && z == 6 -> {
                    if (i + 1 >= zchars.size) break
                    appendZscii(out, (zchars[i] shl 5) or zchars[i + 1])
                    i += 2
                }
                else -> appendZscii(out, alphabets[alphabet * 26 + z - 6])
            }
            alphabet = 0
        }
        return out.toString()
    }

    private fun abbreviation(index: Int): String {
        val wordAddress = memory.readWord(header.abbreviations + 2 * index)
        val zchars = ArrayList<Int>()
        var address = wordAddress * 2
        while (true) {
            val word = memory.readWord(address)
            address += 2
            zchars += (word shr 10) and 31
            zchars += (word shr 5) and 31
            zchars += word and 31
            if (word and 0x8000 != 0) break
        }
        return decodeZChars(zchars, allowAbbreviations = false)
    }

    private fun appendZscii(out: StringBuilder, zscii: Int) {
        val codePoint = zsciiToUnicode(zscii)
        if (codePoint != null) out.appendCodePoint(codePoint)
    }

    /** The Unicode character for a ZSCII output code, or null if it prints nothing. */
    fun zsciiToUnicode(zscii: Int): Int? = when {
        zscii == 13 -> '\n'.code
        zscii in 32..126 -> zscii
        zscii - FIRST_EXTRA in extraChars.indices -> extraChars[zscii - FIRST_EXTRA]
        else -> null
    }

    /** The ZSCII code for typed text; anything with no code becomes '?'. */
    fun unicodeToZscii(codePoint: Int): Int {
        if (codePoint == '\n'.code) return 13
        if (codePoint in 32..126) return codePoint
        val index = extraChars.indexOf(codePoint)
        return if (index >= 0) FIRST_EXTRA + index else '?'.code
    }

    /** Encodes a word as a dictionary key: lower case, cut or padded to a fixed length. */
    fun encodeDictionaryWord(word: String): IntArray {
        val zchars = ArrayList<Int>()
        word.lowercase().codePoints().forEach { addZChars(zchars, unicodeToZscii(it)) }
        while (zchars.size < dictionaryZChars) zchars += 5

        val words = IntArray(dictionaryZChars / 3) { index ->
            (zchars[index * 3] shl 10) or (zchars[index * 3 + 1] shl 5) or zchars[index * 3 + 2]
        }
        words[words.size - 1] = words.last() or 0x8000
        return words
    }

    private fun addZChars(zchars: MutableList<Int>, zscii: Int) {
        val position = alphabetPosition(zscii)
        if (position != null) {
            val alphabet = position / 26
            if (alphabet > 0) zchars += 3 + alphabet
            zchars += position % 26 + 6
        } else {
            zchars += listOf(5, 6, (zscii shr 5) and 31, zscii and 31)
        }
    }

    private fun alphabetPosition(zscii: Int): Int? {
        for (position in alphabets.indices) {
            if (position != ESCAPE_POSITION && alphabets[position] == zscii) return position
        }
        return null
    }

    private fun loadAlphabets(): IntArray {
        val table = header.alphabetTable
        val loaded = if (table != 0) {
            IntArray(78) { memory.readByte(table + it) }
        } else {
            DEFAULT_ALPHABETS.map { it.code }.toIntArray()
        }
        loaded[ESCAPE_POSITION] = 0 // A2 Z-character 6 is always the ZSCII escape.
        loaded[ESCAPE_POSITION + 1] = 13 // A2 Z-character 7 is always a new line.
        return loaded
    }

    private fun loadExtraChars(): IntArray {
        val table = header.unicodeTable
        if (table == 0) return DEFAULT_EXTRA_CHARS.codePoints().toArray()
        return IntArray(memory.readByte(table)) { memory.readWord(table + 1 + 2 * it) }
    }

    private companion object {
        const val FIRST_EXTRA = 155
        const val ESCAPE_POSITION = 52

        // The two spaces in A2 stand for the escape and new line, set in loadAlphabets.
        const val DEFAULT_ALPHABETS = "abcdefghijklmnopqrstuvwxyz" +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
            "  0123456789.,!?_#'\"/\\-:()"

        // Standard table 1: ZSCII 155 to 223.
        const val DEFAULT_EXTRA_CHARS =
            "äöüÄÖÜß»«ëïÿËÏáéíóúýÁÉÍÓÚÝàèìòùÀÈÌÒÙâêîôûÂÊÎÔÛåÅøØãñõÃÑÕæÆçÇþðÞÐ£œŒ¡¿"
    }
}
