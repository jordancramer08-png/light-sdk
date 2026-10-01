package com.thelightphone.listen.podcasts.store

import com.thelightphone.listen.podcasts.feed.Episode
import com.thelightphone.listen.podcasts.feed.ParsedFeed
import com.thelightphone.listen.podcasts.feed.ShowInfo
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeStoresTest {

    private val dir: File = Files.createTempDirectory("listen-episodes").toFile()
    private val file = File(dir, ".state/podcast_episodes.json")
    private var now = 100_000L
    private fun store() = EpisodeStateStore(file) { now }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `positions are saved and read back`() {
        val s = store()
        assertEquals(EpisodeState(), s.get("show", "ep"), "untouched episodes have no row")
        assertFalse(s.savePosition("show", "ep", 120_000, 3_600_000))
        val saved = store().get("show", "ep")
        assertEquals(120_000L, saved.positionMs)
        assertEquals(3_600_000L, saved.durationMs)
        assertEquals(100_000L, saved.lastPlayedAt)
        assertFalse(saved.played)
    }

    @Test
    fun `the last 30 seconds marks it played, once`() {
        val s = store()
        assertTrue(s.savePosition("show", "ep", 3_575_000, 3_600_000))
        assertTrue(s.get("show", "ep").played)
        assertEquals(0L, s.get("show", "ep").positionMs, "the next play starts at the beginning")
        assertFalse(s.savePosition("show", "ep", 3_590_000, 3_600_000), "already played")
    }

    @Test
    fun `95 percent marks a long episode played`() {
        val s = store()
        assertFalse(s.savePosition("s", "e", 6_800_000, 7_200_000))
        assertTrue(s.savePosition("s", "e", 6_850_000, 7_200_000))
        assertFalse(isPodcastFinished(1_000, 0), "unknown length is never finished")
    }

    @Test
    fun `mark played, unplayed, and all played`() {
        val s = store()
        s.savePosition("s", "a", 60_000, 600_000)
        s.markPlayed("s", "a")
        assertTrue(s.get("s", "a").played)
        assertEquals(100_000L, s.get("s", "a").playedAt)
        s.markUnplayed("s", "a")
        assertFalse(s.get("s", "a").played)
        s.markAllPlayed(listOf("s" to "a", "s" to "b", "t" to "c"))
        assertTrue(listOf("a", "b").all { s.get("s", it).played } && s.get("t", "c").played)
        assertEquals(3, store().states.value.size, "one write, saved")
    }

    @Test
    fun `downloads are recorded and cleared, and an empty row is removed`() {
        val s = store()
        val files = DownloadedFiles("ep.mp3", bytes = 50_000_000, chapters = "ep.chapters.json", transcript = "ep.transcript.vtt", transcriptType = "text/vtt")
        s.setDownloaded("show", "ep", files)
        s.setDownloaded("show", "ep2", DownloadedFiles("ep2.m4a", bytes = 10))
        s.setDownloaded("other", "x", DownloadedFiles("x.mp3", bytes = 1))
        assertEquals(mapOf("ep" to files, "ep2" to DownloadedFiles("ep2.m4a", bytes = 10)), store().downloadsOf("show"))
        assertEquals(50_000_011L, s.downloadedBytes())
        assertEquals(listOf("ep.mp3", "ep.chapters.json", "ep.transcript.vtt"), files.allFiles)
        s.clearDownload("show", "ep")
        assertNull(s.get("show", "ep").download)
        assertFalse("show/ep" in s.states.value, "nothing left to remember")
    }

    // ---- New Episodes ----

    private val day = 24L * 60 * 60 * 1000
    private val today = 1_800_000_000_000L
    private fun ep(id: String, daysAgo: Double?) =
        Episode(id = id, title = id, publishedAt = daysAgo?.let { today - (it * day).toLong() }, enclosureUrl = "https://a/$id.mp3")

    @Test
    fun `new episodes are unplayed, recent, after following, newest first`() {
        val shows = listOf(
            Subscription("A", "https://a/rss", title = "A", followedAt = today - 10 * day),
            Subscription("B", "https://b/rss", title = "B", followedAt = today - 60 * day),
            Subscription("C", "https://c/rss", title = "C", followedAt = today - 60 * day, unfollowedAt = today - day),
        )
        val episodes = mapOf(
            "A" to listOf(ep("a-new", 2.0), ep("a-played", 5.0), ep("a-before-follow", 20.0), ep("a-undated", null)),
            "B" to listOf(ep("b-29-days", 29.0), ep("b-31-days", 31.0), ep("b-today", 0.1)),
            "C" to listOf(ep("c-unfollowed", 0.5)),
        )
        val states = mapOf(episodeKey("A", "a-played") to EpisodeState(played = true))
        val list = newEpisodes(shows, episodes, states, now = today)
        assertEquals(listOf("b-today", "a-new", "b-29-days"), list.map { it.episode.id })
        assertEquals("B", list.first().show.title)
    }

    // ---- Feed cache ----

    @Test
    fun `feed list and notes are saved apart and read back`() {
        val show = ShowFiles(File(dir, "Podcasts/abc"))
        assertNull(show.loadSnapshot())
        val feed = ParsedFeed(ShowInfo("Show", "Host"), "Show notes", listOf(ep("e1", 1.0)), mapOf("e1" to "Episode notes"))
        show.save(feed, fetchedAt = 42)
        val snap = show.loadSnapshot()!!
        assertEquals(42L, snap.fetchedAt)
        assertEquals(feed.episodes, snap.episodes)
        assertFalse("Episode notes" in File(dir, "Podcasts/abc/feed.json").readText(), "the list holds no long text")
        assertEquals(FeedNotes(show = "Show notes", episodes = mapOf("e1" to "Episode notes")), show.loadNotes())
        File(dir, "Podcasts/abc/feed.json").writeText("garbage")
        assertNull(show.loadSnapshot(), "a broken copy is just refetched")
    }
}
