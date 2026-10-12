package com.jordan.watch.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Resume, speed and next-up maths. */
class PlaybackMathTest {

    private val twoHours = 120 * 60_000L
    private val thirtyMin = 30 * 60_000L

    @Test
    fun `reopen rewinds five seconds`() {
        assertEquals(595_000, Resume.startPosition(600_000, twoHours))
        assertEquals(0, Resume.startPosition(3_000, twoHours))
        assertEquals(0, Resume.startPosition(0, twoHours))
    }

    @Test
    fun `finished title starts over`() {
        assertEquals(0, Resume.startPosition(twoHours - 60_000, twoHours))
    }

    @Test
    fun `long film is watched in the last two minutes`() {
        // 3% of 2 h = 3.6 min, so the 2 minutes are smaller.
        assertEquals(120_000, Resume.watchedTailMs(twoHours))
        assertTrue(Resume.isWatched(twoHours - 120_000, twoHours))
        assertFalse(Resume.isWatched(twoHours - 121_000, twoHours))
    }

    @Test
    fun `short episode is watched in the last three percent`() {
        // 3% of 30 min = 54 s.
        assertEquals(54_000, Resume.watchedTailMs(thirtyMin))
        assertTrue(Resume.isWatched(thirtyMin - 54_000, thirtyMin))
        assertFalse(Resume.isWatched(thirtyMin - 60_000, thirtyMin))
        assertFalse(Resume.isWatched(5_000, 0))
    }

    @Test
    fun `progress and save timing`() {
        assertEquals(0.5f, Resume.progress(thirtyMin / 2, thirtyMin))
        assertEquals(0f, Resume.progress(10, 0))
        assertTrue(Resume.dueForSave(0, 10_000))
        assertFalse(Resume.dueForSave(0, 9_999))
    }

    @Test
    fun `speed steps run 0_25 to 3_0`() {
        assertEquals(12, Speed.steps.size)
        assertEquals(0.25f, Speed.steps.first())
        assertEquals(3.0f, Speed.steps.last())
        assertEquals(listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f), Speed.quick)
    }

    @Test
    fun `speed snaps clamps and steps`() {
        assertEquals(1.25f, Speed.snap(1.3f))
        assertEquals(0.25f, Speed.snap(0.01f))
        assertEquals(3.0f, Speed.snap(9f))
        assertEquals(1f, Speed.snap(Float.NaN))
        assertEquals(1.25f, Speed.faster(1f))
        assertEquals(3.0f, Speed.faster(3f))
        assertEquals(0.25f, Speed.slower(0.25f))
        assertTrue(Speed.isNormal(1f))
    }

    @Test
    fun `speed labels`() {
        assertEquals("1x", Speed.label(1f))
        assertEquals("1.5x", Speed.label(1.5f))
        assertEquals("0.25x", Speed.label(0.25f))
        assertEquals("2.75x", Speed.label(2.75f))
    }

    private fun movie(id: String, number: Int?, year: Int, collection: String? = "Harry Potter") =
        Movie(id = id, title = id, year = year, collection = collection, collectionNumber = number)

    @Test
    fun `next in collection follows story order`() {
        val one = movie("Stone", 1, 2001)
        val two = movie("Chamber", 2, 2002)
        val three = movie("Azkaban", 3, 2004)
        val other = movie("Up", null, 2009, collection = null)
        val library = listOf(three, other, one, two)
        assertEquals(two, NextUp.nextMovie(one, library))
        assertEquals(three, NextUp.nextMovie(two, library))
        assertNull(NextUp.nextMovie(three, library))
        assertEquals(one, NextUp.previousMovie(two, library))
        assertNull(NextUp.nextMovie(other, library))
    }

    @Test
    fun `collection without numbers falls back to year`() {
        val b = movie("B", null, 1990)
        val a = movie("A", null, 1980)
        assertEquals(b, NextUp.nextMovie(a, listOf(b, a)))
    }

    private fun ep(season: Int, episode: Int) =
        Episode(id = "Show/S${season}E$episode", title = "E$episode", season = season, episode = episode)

    @Test
    fun `next episode crosses seasons and skips specials`() {
        val s1e1 = ep(1, 1)
        val s1e2 = ep(1, 2)
        val s2e1 = ep(2, 1)
        val special = ep(0, 1)
        val all = listOf(s2e1, special, s1e2, s1e1)
        assertEquals(s1e2, NextUp.nextEpisode(s1e1, all))
        assertEquals(s2e1, NextUp.nextEpisode(s1e2, all))
        assertEquals(special, NextUp.nextEpisode(s2e1, all))
        assertNull(NextUp.nextEpisode(special, all))
        assertEquals(s1e2, NextUp.previousEpisode(s2e1, all))
        assertNull(NextUp.previousEpisode(s1e1, all))
    }
}
