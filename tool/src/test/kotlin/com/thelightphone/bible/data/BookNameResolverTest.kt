package com.thelightphone.bible.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val PLAN = ReadingPlan(
    name = "Test Plan",
    year = 2026,
    source = "test",
    totalReadingDays = 1,
    days = emptyList(),
    bookAliases = mapOf(
        "Psalms" to listOf("Psalm", "Pss", "Ps.", "Psa"),
        "Song of Songs" to listOf("Song of Solomon", "The Song of Solomon", "SoS", "Cant"),
    ),
    canonicalBookOrder = listOf("Genesis", "Psalms", "Song of Songs"),
)

class BookNameResolverTest {

    private val resolver = BookNameResolver(PLAN)

    @Test
    fun `resolves a canonical name to itself`() {
        assertEquals("Genesis", resolver.resolve("Genesis"))
    }

    @Test
    fun `resolves an alias to its canonical name`() {
        assertEquals("Psalms", resolver.resolve("Psalm"))
    }

    @Test
    fun `is case-insensitive and ignores periods and extra whitespace`() {
        assertEquals("Psalms", resolver.resolve("  ps.  "))
        assertEquals("Psalms", resolver.resolve("PSALM"))
    }

    @Test
    fun `resolves the translation-file spelling that differs from the plan's canonical name`() {
        // CLAUDE.md 8: the plan's canonical name is "Song of Songs" but translation
        // manifests (and the book's own toc.ncx) call it "Song of Solomon".
        assertEquals("Song of Songs", resolver.resolve("Song of Solomon"))
    }

    @Test
    fun `returns null for a name that matches no book or alias`() {
        assertNull(resolver.resolve("Not A Book"))
    }
}
