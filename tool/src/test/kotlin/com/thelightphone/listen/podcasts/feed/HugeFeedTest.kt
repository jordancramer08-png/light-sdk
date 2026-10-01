package com.thelightphone.listen.podcasts.feed

import com.thelightphone.listen.podcasts.net.HttpResponse
import com.thelightphone.listen.podcasts.net.NetError
import com.thelightphone.listen.podcasts.net.PodcastFetcher
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A big show's feed (thousands of episodes with long notes) and a feed that never ends. */
class HugeFeedTest {

    private fun item(i: Int) =
        """
        <item>
          <title>Episode $i</title>
          <guid>huge-$i</guid>
          <pubDate>Tue, 29 Sep 2026 10:00:00 GMT</pubDate>
          <itunes:duration>${i % 7200}</itunes:duration>
          <enclosure url="https://media.example.com/huge/$i.mp3" type="audio/mpeg" length="${i * 1000}"/>
          <content:encoded><![CDATA[<p>${"Show notes paragraph for episode $i. ".repeat(40)}</p><ul><li>A</li><li>B</li></ul>]]></content:encoded>
        </item>
        """.trimIndent()

    private val head = """<?xml version="1.0"?><rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" """ +
        """xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><title>Huge</title>"""

    @Test
    fun `5,000 episodes with long notes parse from a stream`() {
        val count = 5_000
        val xml = buildString {
            append(head)
            for (i in 1..count) append(item(i))
            append("</channel></rss>")
        }
        val bytes = xml.toByteArray()
        assertTrue(bytes.size > 7_000_000, "the sample really is big: ${bytes.size} bytes")
        val started = System.nanoTime()
        val feed = FeedParser.parse(ByteArrayInputStream(bytes), "https://example.com/huge.xml")
        val ms = (System.nanoTime() - started) / 1_000_000
        println("Parsed ${bytes.size / 1_000_000} MB feed with $count episodes in $ms ms")
        assertEquals(count, feed.episodes.size)
        assertEquals("Episode 4321", feed.episodes[4320].title)
        assertEquals(4_321_000L, feed.episodes[4320].enclosureBytes)
        assertTrue(feed.episodeNotes.getValue(feed.episodes[0].id).endsWith("• A\n• B"))
    }

    @Test
    fun `a feed that never ends is cut off at the size cap`() {
        val endless = object : InputStream() {
            val start = (head + item(1)).toByteArray()
            val repeated = item(2).toByteArray()
            var pos = 0L
            fun byteAt(p: Long): Byte =
                if (p < start.size) start[p.toInt()] else repeated[((p - start.size) % repeated.size).toInt()]
            override fun read(): Int = byteAt(pos++).toInt() and 0xFF
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                for (i in 0 until len) b[off + i] = byteAt(pos++)
                return len
            }
        }
        val fetcher = PodcastFetcher { _, _ -> HttpResponse(200, null, "application/rss+xml", -1, endless) }
        val e = assertFailsWith<NetError.TooLarge> { fetcher.fetchFeed("https://example.com/endless.xml") }
        assertEquals(PodcastFetcher.MAX_FEED_BYTES, e.limitBytes)
    }

    @Test
    fun `a feed that says it's too big isn't even read`() {
        val fetcher = PodcastFetcher { _, _ ->
            HttpResponse(200, null, "application/rss+xml", PodcastFetcher.MAX_FEED_BYTES + 1, ByteArrayInputStream(ByteArray(0)))
        }
        assertFailsWith<NetError.TooLarge> { fetcher.fetchFeed("https://example.com/big.xml") }
    }
}
