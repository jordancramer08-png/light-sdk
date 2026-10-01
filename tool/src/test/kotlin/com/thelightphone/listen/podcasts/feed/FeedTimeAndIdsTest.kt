package com.thelightphone.listen.podcasts.feed

import com.thelightphone.listen.podcasts.PodcastIds
import com.thelightphone.listen.podcasts.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class FeedTimeAndIdsTest {

    @Test
    fun `RFC 822 dates as feeds really write them`() {
        val t = Samples.ms("2026-09-29T10:00:00Z")
        assertEquals(t, FeedTime.parseDate("Tue, 29 Sep 2026 10:00:00 GMT"))
        assertEquals(t, FeedTime.parseDate("Tue, 29 Sep 2026 10:00:00 +0000"))
        assertEquals(t, FeedTime.parseDate("Tue, 29 Sep 2026 06:00:00 -04:00"))
        assertEquals(t, FeedTime.parseDate("Tue, 29 Sep 2026 06:00:00 EDT"))
        assertEquals(t, FeedTime.parseDate("Tuesday, 29 September 2026 10:00:00 UT"))
        assertEquals(t, FeedTime.parseDate("Sun, 29 Sep 2026 10:00 GMT"), "wrong weekday, no seconds")
        assertEquals(t, FeedTime.parseDate("  29 Sept. 2026 10:00:00  "), "no zone = UTC")
        assertEquals(t, FeedTime.parseDate("Tue, 29-Sep-26 10:00:00 Z"))
        assertEquals(Samples.ms("2026-09-29T00:00:00Z"), FeedTime.parseDate("29 Sep 2026"))
        assertEquals(t, FeedTime.parseDate("September 29, 2026 10:00:00 AM GMT"))
        assertEquals(Samples.ms("2026-09-29T22:00:00Z"), FeedTime.parseDate("Sep 29th 2026 10:00 PM"))
        assertEquals(t, FeedTime.parseDate("2026-09-29T10:00:00Z"))
        assertEquals(t, FeedTime.parseDate("2026-09-29 10:00:00"))
        assertNull(FeedTime.parseDate("31 Feb 2026 10:00:00 GMT"))
        assertNull(FeedTime.parseDate("yesterday"))
        assertNull(FeedTime.parseDate(""))
        assertNull(FeedTime.parseDate(null))
    }

    @Test
    fun `durations and clock times`() {
        assertEquals(3_723_000L, FeedTime.parseDuration("1:02:03"))
        assertEquals(3_723_000L, FeedTime.parseDuration("01:02:03.000"))
        assertEquals(3_600_000L, FeedTime.parseDuration("60:00"))
        assertEquals(3_600_500L, FeedTime.parseDuration("3600.5"))
        assertNull(FeedTime.parseDuration("0"))
        assertNull(FeedTime.parseDuration("-5"))
        assertNull(FeedTime.parseDuration("1:2:3:4"))
        assertNull(FeedTime.parseDuration("45 min"))
        assertEquals(62_500L, FeedTime.parseClock("00:01:02,500"))
        assertEquals(62_500L, FeedTime.parseClock("1:02.5"))
        assertEquals(62_500L, FeedTime.parseClock("62.5"))
        assertEquals(0L, FeedTime.parseClock("0"))
    }

    @Test
    fun `feed addresses normalize so the same show matches however it's written`() {
        val n = PodcastIds.normalizeFeedUrl("https://feeds.example.com/rss")
        for (same in listOf("http://Feeds.Example.com/rss/", "HTTPS://feeds.example.com:443/rss", "feed://feeds.example.com/rss#top")) {
            assertEquals(n, PodcastIds.normalizeFeedUrl(same), same)
        }
        assertNotEquals(n, PodcastIds.normalizeFeedUrl("https://feeds.example.com/RSS"), "paths are case-sensitive")
    }

    @Test
    fun `show id is SHA-1 of the normalized address (Podcasts_cmd computes the same)`() {
        assertEquals("a289ecf34023", PodcastIds.showIdFor("http://Feeds.Example.com/rss/"))
        assertEquals(PodcastIds.shortHash("guid-1"), PodcastIds.episodeIdFor(" guid-1 ", "https://a/b.mp3"))
        assertEquals(PodcastIds.shortHash("https://a/b.mp3"), PodcastIds.episodeIdFor("  ", "https://a/b.mp3"))
    }

    @Test
    fun `http becomes https, and relative addresses resolve`() {
        assertEquals("https://a.com/x.mp3", PodcastIds.upgradeToHttps("http://a.com/x.mp3"))
        assertEquals("https://a.com/x.mp3", PodcastIds.upgradeToHttps("HTTP://a.com/x.mp3"))
        assertEquals("https://a.com/x", PodcastIds.upgradeToHttps("//a.com/x"))
        assertEquals("https://a.com/x", PodcastIds.upgradeToHttps("https://a.com/x"))
        assertEquals("https://a.com/b/c.mp3", PodcastIds.resolve("https://a.com/b/feed.xml", "c.mp3"))
        assertEquals("https://a.com/c%20d.mp3", PodcastIds.resolve("https://a.com/b/feed.xml", "/c d.mp3"))
        assertNull(PodcastIds.resolve("https://a.com/", "javascript:alert(1)"))
        assertNull(PodcastIds.resolve(null, "relative.mp3"))
    }
}
