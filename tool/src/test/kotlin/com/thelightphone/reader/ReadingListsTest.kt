package com.thelightphone.reader

import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.ChapterMeta
import com.thelightphone.reader.data.ReadingPosition
import com.thelightphone.reader.data.SourceStamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadingListsTest {

    private fun book(slug: String) = BookMeta(
        slug = slug,
        title = slug,
        author = "Someone",
        chapters = listOf(ChapterMeta(1, "Chapter 1", "001.txt", 100)),
        source = SourceStamp("$slug.epub", 1, 1, 1),
    )

    @Test
    fun listRowsKeepTheListOrder() {
        val books = listOf(book("a"), book("b"), book("c"))
        val rows = listRows(books, emptyMap(), listOf("c", "a", "b"))
        assertEquals(listOf("c", "a", "b"), rows.map { it.meta.slug })
    }

    @Test
    fun listRowsSkipBooksNoLongerOnThePhone() {
        val rows = listRows(listOf(book("a")), emptyMap(), listOf("gone", "a"))
        assertEquals(listOf("a"), rows.map { it.meta.slug })
    }

    @Test
    fun listRowsShowProgress() {
        val positions = mapOf("a" to ReadingPosition("a", 1, 50, 0))
        val rows = listRows(listOf(book("a"), book("b")), positions, listOf("a", "b"))
        assertEquals(listOf("50% read", "Not started"), rows.map { it.statusText })
    }

    @Test
    fun neighbourAboveAndBelow() {
        val shown = listOf("a", "b", "c")
        assertEquals("a", neighbourSlug(shown, "b", up = true))
        assertEquals("c", neighbourSlug(shown, "b", up = false))
    }

    @Test
    fun noNeighbourAtTheEnds() {
        val shown = listOf("a", "b", "c")
        assertNull(neighbourSlug(shown, "a", up = true))
        assertNull(neighbourSlug(shown, "c", up = false))
        assertNull(neighbourSlug(shown, "missing", up = true))
    }

    @Test
    fun listNamesAreTidied() {
        assertEquals("Summer reads", cleanListName("  Summer   reads \n"))
        assertNull(cleanListName("   "))
        assertNull(cleanListName(null))
    }
}
