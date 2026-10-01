package com.thelightphone.listen.podcasts.feed

import com.thelightphone.listen.podcasts.PodcastIds
import com.thelightphone.listen.podcasts.Samples
import com.thelightphone.listen.podcasts.chapters.ChapterSource
import com.thelightphone.listen.podcasts.chapters.pscChapters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedParserTest {

    private val messyUrl = "http://example.com/tea/feed.xml"
    private val messy by lazy { FeedParser.parse(Samples.file("feed-messy.xml").inputStream(), messyUrl) }
    private fun messyEpisode(title: String) = messy.episodes.single { it.title == title }

    @Test
    fun `show details, with the itunes namespace under an odd prefix`() {
        val show = messy.show
        assertEquals("Tea & Theology &xxe;", show.title, "the external entity is never expanded")
        assertEquals("Sample Host", show.author)
        assertEquals("http://example.com/art/tea-3000.jpg", show.artUrl, "itunes:image only")
        assertEquals("http://example.com/tea", show.link)
        assertEquals("https://feeds.example.com/tea", show.newFeedUrl)
    }

    @Test
    fun `show description is the longer of itunes summary and description`() {
        assertTrue(messy.showNotes.startsWith("A longer summary of the show"))
    }

    @Test
    fun `episodes are the items with audio, in feed order, without repeats`() {
        assertEquals(
            listOf(
                "Grace & Truth ’24", "No guid here", "US-style date", "Wrong weekday, two-digit year", "ISO date",
                "Garbage date", "Relative enclosure", "Bad characters � and � and �", "Only an itunes title",
            ),
            messy.episodes.map { it.title },
        )
    }

    @Test
    fun `a full episode`() {
        val ep = messy.episodes.first()
        assertEquals("tea-episode-1", ep.guid)
        assertEquals("5658bbaf5cf7", ep.id)
        assertEquals(Samples.ms("2026-09-29T10:00:00Z"), ep.publishedAt)
        assertEquals((3600 + 2 * 60 + 3) * 1000L, ep.durationMs)
        assertEquals("http://media.example.com/tea/ep1.mp3", ep.enclosureUrl, "kept as written; upgraded when fetched")
        assertEquals("audio/mpeg", ep.enclosureType)
        assertEquals(12345678L, ep.enclosureBytes)
        assertEquals("https://media.example.com/tea/ep1-chapters.json", ep.chaptersUrl)
        assertEquals("application/json+chapters", ep.chaptersType)
        assertEquals(
            listOf("application/x-subrip" to "https://media.example.com/tea/ep1.srt", "text/vtt" to "https://media.example.com/tea/ep1.vtt"),
            ep.transcripts.map { it.type to it.url },
        )
        assertEquals("en", ep.transcripts[1].language)
        assertTrue(ep.feedHasChapters)
        assertTrue(ep.hasTranscript)
        assertEquals("Full notes for episode one.\n\n• First point\n• Second point", messy.episodeNotes[ep.id], "content:encoded wins")
        val psc = pscChapters(ep.pscChapters)!!
        assertEquals(ChapterSource.PSC, psc.source)
        assertEquals(listOf(0L to "Welcome", 330_500L to "Main topic", 3_600_000L to "Wrap-up"), psc.chapters.map { it.startMs to it.title })
        assertEquals("https://example.com/topic", psc.chapters[1].url)
    }

    @Test
    fun `missing guid falls back to the enclosure address`() {
        val ep = messyEpisode("No guid here")
        assertNull(ep.guid)
        assertEquals(PodcastIds.shortHash("http://media.example.com/tea/ep2.mp3"), ep.id)
        assertNull(ep.enclosureBytes, "a length that isn't a number is ignored")
        assertEquals(3_600_000L, ep.durationMs)
        assertEquals("Only a description.", messy.episodeNotes[ep.id], "description when there's no content:encoded")
    }

    @Test
    fun `odd dates are read, and unreadable ones are null`() {
        assertEquals(Samples.ms("2026-09-29T15:00:00Z"), messyEpisode("No guid here").publishedAt, "no weekday, no seconds, EST")
        assertEquals(Samples.ms("2026-10-01T04:30:00Z"), messyEpisode("US-style date").publishedAt)
        assertEquals(Samples.ms("2026-10-01T06:00:00Z"), messyEpisode("Wrong weekday, two-digit year").publishedAt)
        assertEquals(Samples.ms("2026-09-15T12:00:00Z"), messyEpisode("ISO date").publishedAt)
        assertNull(messyEpisode("Garbage date").publishedAt)
        assertNull(messyEpisode("Garbage date").durationMs, "\"about an hour\" isn't a duration")
    }

    @Test
    fun `itunes summary is the last fallback for notes`() {
        val ep = messyEpisode("US-style date")
        assertEquals("Only an itunes summary.", messy.episodeNotes[ep.id])
        assertEquals(2_700_000L, ep.durationMs)
    }

    @Test
    fun `relative enclosure is made absolute against the feed`() {
        assertEquals("http://example.com/audio/ep8.mp3", messyEpisode("Relative enclosure").enclosureUrl)
    }

    @Test
    fun `raw HTML inside the XML and bad character codes don't break anything`() {
        val ep = messyEpisode("Bad characters � and � and �")
        assertEquals("Raw HTML right inside the XML,\n\nnot escaped & not in CDATA.", messy.episodeNotes[ep.id])
    }

    @Test
    fun `itunes title is used when there's no title`() {
        assertTrue(messy.episodes.any { it.title == "Only an itunes title" })
    }

    @Test
    fun `HTML-heavy show notes become clean text`() {
        val feed = FeedParser.parse(Samples.file("feed-html-notes.xml").inputStream(), "https://example.com/notes.xml")
        assertEquals("Hello & welcome to Notes Heavy.\n\nSecond paragraph.", feed.showNotes, "escaped HTML in <description>")
        assertEquals(
            """
            In this episode

            We talk about things — lots of them. It’s fun.

            • The first thing
            • The second thing

            3. Third
            4. Fourth

            Line one
            Line two
            Line three

            Visit our website (https://example.com/show) or https://example.com/donate. Email host@example.com.

            Cell A Cell B

            Deeply nested end.
            """.trimIndent(),
            feed.episodeNotes.values.single(),
        )
    }

    @Test
    fun `a feed with no descriptions has empty notes`() {
        val feed = FeedParser.parse(Samples.file("feed-no-descriptions.xml").inputStream(), null)
        assertEquals("", feed.showNotes)
        assertTrue(feed.episodeNotes.isEmpty())
        assertEquals(2, feed.episodes.size)
        assertFalse(feed.episodes.any { it.hasNotes })
        assertEquals("https://example.com/bare.png", feed.show.artUrl, "<image><url>")
        assertEquals("editor@example.com (Bare Editor)", feed.show.author, "managingEditor as the last author fallback")
        assertNull(feed.episodes[1].publishedAt)
    }

    @Test
    fun `image tag only, owner name, and a Latin-1 feed`() {
        val feed = FeedParser.parse(Samples.file("feed-image-tag-only.xml").inputStream(), "http://example.com/cafe.xml")
        assertEquals("Café Talk", feed.show.title)
        assertEquals("René Owner", feed.show.author)
        assertEquals("http://example.com/cafe.jpg", feed.show.artUrl)
        assertEquals("Plain text description.\nSecond line of it.", feed.showNotes, "plain text keeps its line breaks")
        assertEquals("Episode à one", feed.episodes.single().title)
    }

    @Test
    fun `link is an element in feeds, while br inside raw description HTML still breaks lines`() {
        val feed = FeedParser.parse(
            "<rss><channel><title>T</title><link>https://example.com/</link>" +
                "<item><guid>g</guid><enclosure url='https://a/b.mp3'/><description>One<br>Two</description></item></channel></rss>",
            null,
        )
        assertEquals("https://example.com/", feed.show.link)
        assertEquals("One\nTwo", feed.episodeNotes.values.single())
    }

    @Test
    fun `a web page is not a feed`() {
        assertFailsWith<NotAFeedException> {
            FeedParser.parse("<!DOCTYPE html><html><body><h1>Oops</h1></body></html>", null)
        }
        assertFailsWith<NotAFeedException> { FeedParser.parse("", null) }
    }

    @Test
    fun `hostile nesting and a giant description stay bounded`() {
        val deep = "<rss><channel><title>T</title>" + "<x>".repeat(100_000) + "</channel></rss>"
        assertEquals("T", FeedParser.parse(deep, null).show.title)

        val huge = "<rss><channel><title>T</title><item><guid>g</guid><enclosure url='https://a/b.mp3'/>" +
            "<description>" + "word ".repeat(1_000_000) + "</description></item></channel></rss>"
        val notes = FeedParser.parse(huge, null).episodeNotes.values.single()
        assertTrue(notes.length <= FeedParser.MAX_NOTES_CHARS)
    }
}
