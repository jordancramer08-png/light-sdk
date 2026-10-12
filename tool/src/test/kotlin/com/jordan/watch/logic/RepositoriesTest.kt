package com.jordan.watch.logic

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoriesTest {

    private val dir: File = Files.createTempDirectory("watch-repos").toFile()
    private val store = StateStore(File(dir, ".state"))
    private val repos = Repositories(store).apply { load() }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `positions are saved and survive a reload`() {
        repos.playback.savePosition("a", 60_000, 7_200_000, 1.5f, nowMs = 10)
        assertEquals(WatchState.InProgress, repos.playback.watchState("a"))

        val again = Repositories(store).apply { load() }
        assertEquals(Position(60_000, 1.5f, 10), again.playback.position("a"))
    }

    @Test
    fun `reaching the end marks watched, unwatch starts over`() {
        repos.playback.savePosition("a", 7_150_000, 7_200_000, 1f, nowMs = 10)
        assertEquals(WatchState.Watched, repos.playback.watchState("a"))
        assertEquals(mapOf("a" to 10L), store.loadWatched())

        repos.playback.markUnwatched("a")
        assertEquals(WatchState.Unwatched, repos.playback.watchState("a"))
        assertEquals(0, store.loadPositions()["a"]!!.positionMs)
    }

    @Test
    fun `continue watching is newest first and skips watched`() {
        repos.playback.savePosition("old", 1_000, 0, 1f, nowMs = 1)
        repos.playback.savePosition("new", 1_000, 0, 1f, nowMs = 3)
        repos.playback.savePosition("done", 1_000, 0, 1f, nowMs = 2)
        repos.playback.markWatched("done", nowMs = 4)
        assertEquals(listOf("new", "old"), repos.playback.inProgressIds())
    }

    @Test
    fun `watchlist adds once, toggles and keeps order`() {
        repos.watchlist.add("b")
        repos.watchlist.add("a")
        repos.watchlist.add("b")
        assertEquals(listOf("b", "a"), store.loadWatchlist())
        repos.watchlist.toggle("b")
        assertFalse(repos.watchlist.contains("b"))
        assertEquals(listOf("a"), repos.watchlist.ids.value)
    }

    @Test
    fun `settings update is written`() {
        repos.settings.update { it.copy(gridColumns = 2, skipSeconds = 30) }
        assertEquals(2, store.loadSettings().gridColumns)
        assertEquals(30, repos.settings.settings.value.skipSeconds)
    }

    @Test
    fun `a failed write keeps the change in memory`() {
        // A plain file where the .state folder should be: every write fails.
        val blocked = File(dir, "blocked").apply { writeText("x") }
        val broken = Repositories(StateStore(File(blocked, ".state"))).apply { load() }
        broken.watchlist.add("a")
        assertTrue(broken.watchlist.contains("a"))
    }
}
