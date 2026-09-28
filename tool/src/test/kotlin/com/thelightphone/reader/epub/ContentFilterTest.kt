package com.thelightphone.reader.epub

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentFilterTest {

    private fun prose(paragraphs: Int, wordsPerParagraph: Int = 20): String =
        (1..paragraphs).joinToString("\n\n") { i ->
            (1..wordsPerParagraph).joinToString(" ") { "word$it" } + " paragraph $i."
        }

    @Test
    fun tooShortIsNonContent() {
        assertTrue(isNonContent(title = "Chapter 1", text = "Short filler text."))
    }

    @Test
    fun longEnoughProseIsContent() {
        assertFalse(isNonContent(title = "Chapter 1", text = prose(5)))
    }

    @Test
    fun nonContentTitleIsFilteredEvenIfLong() {
        assertTrue(isNonContent(title = "Copyright Page", text = prose(5)))
    }

    @Test
    fun nonContentTitleMatchIsCaseAndColonInsensitive() {
        assertTrue(isNonContent(title = "COVER:", text = prose(5)))
    }

    @Test
    fun similarButDifferentTitleIsNotFiltered() {
        // "Dedication Day" merely contains the word "Dedication" and must survive.
        assertFalse(isNonContent(title = "Dedication Day", text = prose(5)))
    }

    @Test
    fun alsoByPrefixIsFiltered() {
        assertTrue(isNonContent(title = "Also by Jane Author", text = prose(5)))
    }

    @Test
    fun adSlugTitleIsFiltered() {
        assertTrue(isNonContent(title = "some-site.com", text = prose(5)))
    }

    @Test
    fun listingPageWithManyShortParagraphsIsFiltered() {
        // 15 short entries: comfortably over MIN_CONTENT_CHARS in total, but each
        // paragraph is well under MAX_LISTING_AVG_PARAGRAPH_CHARS.
        val listing = (1..15).joinToString("\n\n") { "Entry $it in the appendix list" }
        assertTrue(isNonContent(title = "Contents", text = listing))
    }

    @Test
    fun praisePageIsFiltered() {
        val text = "Praise for The Book\n\n" + prose(4)
        assertTrue(isNonContent(title = null, text = text))
    }

    @Test
    fun advancePraisePageIsFiltered() {
        val text = "Advance Praise for The Book\n\n" + prose(4)
        assertTrue(isNonContent(title = null, text = text))
    }

    @Test
    fun praiseMentionedMidChapterIsNotFiltered() {
        val text = prose(5) + "\n\nHer praise for the choir was well earned."
        assertFalse(isNonContent(title = "Chapter 3", text = text))
    }
}
