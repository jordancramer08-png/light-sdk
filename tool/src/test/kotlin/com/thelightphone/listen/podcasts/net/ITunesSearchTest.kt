package com.thelightphone.listen.podcasts.net

import com.thelightphone.listen.podcasts.Samples
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ITunesSearchTest {

    @Test
    fun `results with a feed, best art, no duplicates`() {
        val results = ITunesSearch.parseResults(Samples.text("itunes-search.json"))
        assertEquals(
            listOf(
                SearchResult("Ask a Pastor (Sample)", "Sample Ministries", "https://feeds.example.com/ask", "https://art.example.com/600.jpg", 1111, 2100, "Christianity"),
                SearchResult("Small Art Only", "Old Host", "http://old.example.com/rss", "https://art.example.com/small.jpg", 3333, null, null),
            ),
            results,
        )
        assertTrue(ITunesSearch.parseResults("<html>error</html>").isEmpty())
        assertTrue(ITunesSearch.parseResults("""{"resultCount":0,"results":[]}""").isEmpty())
    }

    @Test
    fun `search address`() {
        assertEquals(
            "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=25&term=ask+pastor+%26+john",
            ITunesSearch.searchUrl("ask pastor & john", 25),
        )
    }

    @Test
    fun `search asks Apple and reads the answer`() {
        val asked = mutableListOf<String>()
        val search = ITunesSearch({ url, _ ->
            asked += url
            val body = Samples.text("itunes-search.json").toByteArray()
            HttpResponse(200, null, "text/javascript; charset=utf-8", body.size.toLong(), ByteArrayInputStream(body))
        })
        assertEquals(2, search.search("  getty  ").size)
        assertEquals(listOf(ITunesSearch.searchUrl("getty", 25)), asked)
        assertTrue(search.search("   ").isEmpty(), "a blank search doesn't call Apple")
        assertEquals(1, asked.size)
    }

    @Test
    fun `no more than the limit per minute`() {
        var now = 0L
        val limiter = SearchLimiter(maxCalls = 2, windowMs = 60_000, clock = { now })
        limiter.acquire()
        now = 1_000
        limiter.acquire()
        now = 2_000
        assertEquals(58_000L, assertFailsWith<SearchLimiter.TooSoon> { limiter.acquire() }.waitMs)
        now = 60_000
        limiter.acquire() // the first call has aged out
    }
}
