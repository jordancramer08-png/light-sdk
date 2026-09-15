package com.thelightphone.bible.data

/** One verse from a converted chapter file: `<number>|<text>` (CLAUDE.md 6). */
data class Verse(val number: Int, val text: String)

/** Parses a chapter file's lines into [Verse]s. Blank lines are skipped; a line with no `|` is dropped. */
fun parseVerses(chapterText: String): List<Verse> =
    chapterText.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull(::parseVerseLine)
        .toList()

private fun parseVerseLine(line: String): Verse? {
    val separator = line.indexOf('|')
    if (separator < 0) return null
    val number = line.substring(0, separator).trim().toIntOrNull() ?: return null
    return Verse(number, line.substring(separator + 1))
}

/** Verses [startVerse]..[endVerse] inclusive, or all of [verses] when either bound is missing. */
fun filterVerseRange(verses: List<Verse>, startVerse: Int?, endVerse: Int?): List<Verse> {
    if (startVerse == null || endVerse == null) return verses
    return verses.filter { it.number in startVerse..endVerse }
}
