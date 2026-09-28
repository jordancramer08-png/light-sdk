package com.thelightphone.reader.epub

/**
 * How many words are in [text]: runs of non-space characters that hold at least one
 * letter or digit. A lone dash or "* * *" isn't a word; "don't" and "twenty-one" are one.
 */
fun countWords(text: String): Int {
    var words = 0
    var inWord = false
    var wordHasLetter = false
    for (c in text) {
        if (c.isWhitespace()) {
            if (inWord && wordHasLetter) words++
            inWord = false
            wordHasLetter = false
        } else {
            inWord = true
            if (c.isLetterOrDigit()) wordHasLetter = true
        }
    }
    if (inWord && wordHasLetter) words++
    return words
}
