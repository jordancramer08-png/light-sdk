package com.thelightphone.listen.podcasts

import com.thelightphone.listen.podcasts.feed.Episode
import com.thelightphone.listen.podcasts.store.EpisodeSort
import com.thelightphone.listen.podcasts.store.EpisodeState
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class PodcastTextTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = Samples.ms("2026-10-01T15:00:00Z") // 11 am in New York

    @Test
    fun `dates read naturally`() {
        assertEquals("Today", episodeDate(Samples.ms("2026-10-01T05:00:00Z"), now, zone))
        assertEquals("Yesterday", episodeDate(Samples.ms("2026-10-01T03:00:00Z"), now, zone), "11 pm the night before, local time")
        assertEquals("Sep 29", episodeDate(Samples.ms("2026-09-29T15:00:00Z"), now, zone))
        assertEquals("Dec 24, 2025", episodeDate(Samples.ms("2025-12-24T15:00:00Z"), now, zone))
        assertEquals("", episodeDate(null, now, zone))
    }

    @Test
    fun `episode detail line`() {
        val ep = Episode(id = "e", title = "T", publishedAt = Samples.ms("2026-09-29T15:00:00Z"), durationMs = 3_720_000, enclosureUrl = "https://a/e.mp3")
        assertEquals("Sep 29 · 1 hr 2 min", episodeDetail(ep, EpisodeState(), now, zone))
        assertEquals("Sep 29 · 1 hr 2 min · 52 min left", episodeDetail(ep, EpisodeState(positionMs = 600_000), now, zone))
        assertEquals("Sep 29 · 1 hr 2 min · Played", episodeDetail(ep, EpisodeState(played = true), now, zone))
        assertEquals("", episodeDetail(ep.copy(publishedAt = null, durationMs = null), EpisodeState(), now, zone))
    }

    @Test
    fun `newest or oldest first, undated last`() {
        fun ep(id: String, at: Long?) = Episode(id = id, title = id, publishedAt = at, enclosureUrl = "https://a/$id.mp3")
        val eps = listOf(ep("b", 2), ep("x", null), ep("a", 1), ep("c", 3))
        assertEquals(listOf("c", "b", "a", "x"), sortEpisodes(eps, EpisodeSort.NEWEST).map { it.id })
        assertEquals(listOf("a", "b", "c", "x"), sortEpisodes(eps, EpisodeSort.OLDEST).map { it.id })
    }
}
