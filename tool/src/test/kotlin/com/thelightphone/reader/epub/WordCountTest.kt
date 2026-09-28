package com.thelightphone.reader.epub

import kotlin.test.Test
import kotlin.test.assertEquals

class WordCountTest {

    @Test
    fun countsWordsAcrossSpacesAndParagraphs() {
        assertEquals(6, countWords("It was a dark\n\nand stormy"))
    }

    @Test
    fun contractionsAndHyphensAreOneWord() {
        assertEquals(3, countWords("don't twenty-one  night."))
    }

    @Test
    fun punctuationOnItsOwnIsNotAWord() {
        assertEquals(2, countWords("Wait — * * * then"))
    }

    @Test
    fun emptyTextHasNoWords() {
        assertEquals(0, countWords(""))
        assertEquals(0, countWords("  \n\n "))
    }
}
