package com.jordan.watch.logic

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StateStoreTest {

    private val dir: File = Files.createTempDirectory("watch-state").toFile()
    private val store = StateStore(File(dir, ".state"))

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `missing files read as empty or defaults`() {
        assertEquals(emptyMap(), store.loadPositions())
        assertEquals(emptyMap(), store.loadWatched())
        assertEquals(emptyList(), store.loadWatchlist())
        assertEquals(WatchSettings(), store.loadSettings())
    }

    @Test
    fun `everything round-trips`() {
        val positions = mapOf("_Singles/Up (2009)" to Position(positionMs = 61_000, speed = 1.25f, lastPlayedAt = 5))
        store.savePositions(positions)
        store.saveWatched(mapOf("a" to 1L))
        store.saveWatchlist(listOf("b", "a"))
        store.saveSettings(WatchSettings(gridColumns = 2))

        assertEquals(positions, store.loadPositions())
        assertEquals(mapOf("a" to 1L), store.loadWatched())
        assertEquals(listOf("b", "a"), store.loadWatchlist())
        assertEquals(2, store.loadSettings().gridColumns)
    }

    @Test
    fun `write leaves no tmp file behind`() {
        store.saveWatchlist(listOf("a"))
        store.saveWatchlist(listOf("a", "b"))
        val names = File(dir, ".state").list()!!.toList()
        assertTrue("watchlist.json" in names)
        assertFalse(names.any { it.endsWith(".tmp") })
    }

    @Test
    fun `broken file reads as empty and is not deleted`() {
        val file = File(dir, ".state/positions.json")
        File(dir, ".state").mkdirs()
        file.writeText("{ not json")
        assertEquals(emptyMap(), store.loadPositions())
        assertTrue(file.exists())
    }

    @Test
    fun `unknown and missing settings fields are tolerated`() {
        val file = File(dir, ".state/settings.json")
        File(dir, ".state").mkdirs()
        file.writeText("""{ "skipSeconds": 30, "fromTheFuture": true }""")
        val settings = store.loadSettings()
        assertEquals(30, settings.skipSeconds)
        assertEquals("DARK", settings.theme)
    }
}
