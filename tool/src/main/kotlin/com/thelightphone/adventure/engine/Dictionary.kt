// Written from the Z-Machine Standards Document 1.1. No code is ported from JSZM or ifvms.js.
package com.thelightphone.adventure.engine

/** One typed word: where it sits in the line, and its dictionary entry (0 if unknown). */
data class Token(val text: String, val start: Int, val entryAddress: Int)

/** A dictionary (Standard section 13). Stories may hold more than one, so the address is given. */
class Dictionary(
    private val memory: StoryMemory,
    private val text: ZText,
    address: Int,
) {
    private val separators: Set<Int>
    private val entryLength: Int
    private val firstEntry: Int
    private val sorted: Boolean
    private val keyBytes = text.dictionaryZChars / 3 * 2

    /** Number of words. */
    val size: Int

    init {
        val separatorCount = memory.readByte(address)
        separators = (1..separatorCount).map { memory.readByte(address + it) }.toSet()
        val afterSeparators = address + 1 + separatorCount
        entryLength = memory.readByte(afterSeparators)
        val rawCount = memory.readWord(afterSeparators + 1).toShort().toInt()
        size = kotlin.math.abs(rawCount)
        sorted = rawCount >= 0 // A negative count marks an unsorted dictionary.
        firstEntry = afterSeparators + 3
    }

    fun entryAddress(index: Int): Int = firstEntry + index * entryLength

    /** The word stored at an entry, cut to the dictionary's key length. */
    fun word(index: Int): String = text.decode(entryAddress(index)).text

    /** Every word, each cut to the dictionary's key length. */
    fun words(): List<String> = (0 until size).map(::word)

    /** The entry address for a word, or 0 if it is not in the dictionary. */
    fun lookup(word: String): Int {
        val key = text.encodeDictionaryWord(word).flatMap { listOf(it shr 8, it and 0xFF) }
        return if (sorted) binarySearch(key) else linearSearch(key)
    }

    private fun binarySearch(key: List<Int>): Int {
        var low = 0
        var high = size - 1
        while (low <= high) {
            val middle = (low + high) / 2
            val order = compare(key, entryAddress(middle))
            when {
                order == 0 -> return entryAddress(middle)
                order < 0 -> high = middle - 1
                else -> low = middle + 1
            }
        }
        return 0
    }

    private fun linearSearch(key: List<Int>): Int {
        for (index in 0 until size) {
            if (compare(key, entryAddress(index)) == 0) return entryAddress(index)
        }
        return 0
    }

    private fun compare(key: List<Int>, address: Int): Int {
        for (offset in 0 until keyBytes) {
            val difference = key[offset] - memory.readByte(address + offset)
            if (difference != 0) return difference
        }
        return 0
    }

    /** Splits a line at spaces and separators; each separator is a word of its own. */
    fun tokenize(line: String): List<Token> {
        val tokens = ArrayList<Token>()
        var start = -1
        fun finishWord(end: Int) {
            if (start >= 0) tokens += token(line.substring(start, end), start)
            start = -1
        }
        line.forEachIndexed { index, char ->
            when {
                char == ' ' -> finishWord(index)
                text.unicodeToZscii(char.code) in separators -> {
                    finishWord(index)
                    tokens += token(char.toString(), index)
                }
                start < 0 -> start = index
            }
        }
        finishWord(line.length)
        return tokens
    }

    private fun token(word: String, start: Int) = Token(word, start, lookup(word))
}
