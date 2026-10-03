package com.thelightphone.listen.podcasts

import com.thelightphone.listen.playback.formatTime
import com.thelightphone.listen.podcasts.net.HttpResponse
import com.thelightphone.listen.podcasts.net.HttpTransport
import com.thelightphone.listen.podcasts.net.NetError
import com.thelightphone.listen.podcasts.net.PodcastFetcher
import com.thelightphone.listen.podcasts.transcripts.TranscriptFormat
import com.thelightphone.listen.podcasts.transcripts.Transcripts
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The polish pass: very long transcripts, feeds that moved, and dead feeds. */
class PolishTest {

    private fun vttTime(ms: Long) = formatTime(ms).let { if (it.count { c -> c == ':' } == 1) "00:$it" else it } + ".000"

    @Test
    fun `a three-hour timed transcript reads fast and finds the line quickly`() {
        val cues = 5_400 // one every 2 seconds for 3 hours
        val vtt = buildString {
            append("WEBVTT\n\n")
            for (i in 0 until cues) {
                val start = i * 2_000L
                append("${vttTime(start)} --> ${vttTime(start + 2_000)}\n")
                append("<v Speaker ${i % 3}>Line number $i of a very long conversation about many things.\n\n")
            }
        }
        val started = System.nanoTime()
        val t = Transcripts.parse(TranscriptFormat.VTT, vtt)!!
        val parseMs = (System.nanoTime() - started) / 1_000_000
        println("Parsed a ${vtt.length / 1024} KB, $cues-line transcript in $parseMs ms")
        assertEquals(cues, t.lines.size)
        assertTrue(t.timed)
        val lookups = System.nanoTime()
        for (ms in 0L until 3 * 3_600_000L step 250) t.lineAt(ms) // every 250 ms of 3 hours, as Now Playing does
        val lookupMs = (System.nanoTime() - lookups) / 1_000_000
        println("43,200 line lookups in $lookupMs ms")
        assertEquals(4_000, t.lineAt(8_000_500))
        assertTrue(lookupMs < 1_000)
    }

    private fun feed(extra: String = "") =
        """<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>Moved</title>$extra
           <item><guid>1</guid><enclosure url="https://a.com/1.mp3" type="audio/mpeg"/></item></channel></rss>"""

    private fun answering(routes: Map<String, () -> HttpResponse>) = HttpTransport { url, _ ->
        (routes[url] ?: { HttpResponse(404, null, null, 0, ByteArrayInputStream(ByteArray(0))) })()
    }

    private fun ok(body: String) = { HttpResponse(200, null, "application/rss+xml", body.length.toLong(), ByteArrayInputStream(body.toByteArray())) }

    @Test
    fun `a feed that moved with a permanent redirect is followed and remembered`() {
        val fetcher = PodcastFetcher(
            answering(
                mapOf(
                    "https://old.com/rss" to { HttpResponse(301, "https://new.com/rss", null, 0, ByteArrayInputStream(ByteArray(0))) },
                    "https://new.com/rss" to ok(feed()),
                ),
            ),
        )
        val f = fetcher.fetchFeed("http://old.com/rss")
        assertEquals("https://new.com/rss", f.movedTo)
        assertEquals("Moved", f.feed.show.title)
    }

    @Test
    fun `a feed that says it moved (itunes new-feed-url) is remembered`() {
        val fetcher = PodcastFetcher(answering(mapOf("https://old.com/rss" to ok(feed("<itunes:new-feed-url>https://new.com/rss</itunes:new-feed-url>")))))
        assertEquals("https://new.com/rss", fetcher.fetchFeed("https://old.com/rss").movedTo)
        val same = PodcastFetcher(answering(mapOf("https://old.com/rss" to ok(feed()))))
        assertNull(same.fetchFeed("https://old.com/rss").movedTo, "a feed that hasn't moved")
    }

    @Test
    fun `a dead feed says so`() {
        val fetcher = PodcastFetcher(
            answering(mapOf("https://gone.com/rss" to { HttpResponse(410, null, null, 0, ByteArrayInputStream(ByteArray(0))) })),
        )
        val e = assertFailsWith<NetError.HttpStatus> { fetcher.fetchFeed("https://gone.com/rss") }
        assertEquals("Not found (the address may have changed).", e.message)
        assertFailsWith<NetError.HttpStatus> { fetcher.fetchFeed("https://nothing-here.com/rss") }
    }
}
