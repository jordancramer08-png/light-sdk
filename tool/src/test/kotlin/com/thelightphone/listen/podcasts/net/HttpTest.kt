package com.thelightphone.listen.podcasts.net

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A pretend web: each address answers with a fixed response or throws. Records what was asked. */
private class FakeWeb(private val routes: Map<String, () -> HttpResponse>) : HttpTransport {
    val asked = mutableListOf<String>()
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        asked += url
        return (routes[url] ?: { HttpResponse(404, null, null, -1, ByteArrayInputStream(ByteArray(0))) })()
    }
}

private fun ok(body: String) = { HttpResponse(200, null, "text/plain; charset=utf-8", body.length.toLong(), ByteArrayInputStream(body.toByteArray())) }
private fun redirect(code: Int, to: String) = { HttpResponse(code, to, null, 0, ByteArrayInputStream(ByteArray(0))) }
private fun fails(e: IOException): () -> HttpResponse = { throw e }

class HttpTest {

    @Test
    fun `http is upgraded before it's ever requested`() {
        val web = FakeWeb(mapOf("https://a.com/feed" to ok("hi")))
        val opened = Http.open(web, "http://a.com/feed")
        assertEquals("hi", Http.readText(opened, 100))
        assertEquals(listOf("https://a.com/feed"), web.asked)
    }

    @Test
    fun `tracking redirects through http are followed over https`() {
        val web = FakeWeb(
            mapOf(
                "https://dts.podtrac.example/redirect.mp3/media.example.com/ep.mp3" to redirect(302, "http://media.example.com/ep.mp3"),
                "https://media.example.com/ep.mp3" to redirect(307, "/cdn/ep.mp3?token=1"),
                "https://media.example.com/cdn/ep.mp3?token=1" to ok("audio"),
            ),
        )
        val opened = Http.open(web, "https://dts.podtrac.example/redirect.mp3/media.example.com/ep.mp3")
        assertEquals("https://media.example.com/cdn/ep.mp3?token=1", opened.url)
        assertNull(opened.movedTo, "temporary redirects don't move the feed")
        assertTrue(web.asked.all { it.startsWith("https://") })
        opened.close()
    }

    @Test
    fun `a permanent redirect on the first hop means the feed moved`() {
        val web = FakeWeb(mapOf("https://old.com/rss" to redirect(301, "https://new.com/rss"), "https://new.com/rss" to ok("x")))
        Http.open(web, "https://old.com/rss").use { assertEquals("https://new.com/rss", it.movedTo) }
    }

    @Test
    fun `too many redirects stop`() {
        val routes = (0..20).associate { "https://loop.com/$it" to redirect(302, "https://loop.com/${it + 1}") }
        assertFailsWith<NetError.TooManyRedirects> { Http.open(FakeWeb(routes), "https://loop.com/0") }
    }

    @Test
    fun `errors in words`() {
        val web = FakeWeb(
            mapOf(
                "https://a.com/gone" to { HttpResponse(410, null, null, 0, ByteArrayInputStream(ByteArray(0))) },
                "https://insecure.com/rss" to fails(SSLHandshakeException("no TLS here")),
                "https://secure.com/rss" to fails(SSLHandshakeException("bad cert")),
                "https://nowhere.invalid/rss" to fails(UnknownHostException("nowhere.invalid")),
            ),
        )
        assertEquals(410, assertFailsWith<NetError.HttpStatus> { Http.open(web, "https://a.com/gone") }.code)
        val secure = assertFailsWith<NetError.NeedsSecureLink> { Http.open(web, "http://insecure.com/rss") }
        assertEquals("http://insecure.com/rss", secure.url)
        // An https address that fails TLS isn't an "http only" problem.
        assertFailsWith<SSLHandshakeException> { Http.open(web, "https://secure.com/rss") }
        assertFailsWith<NetError.NoConnection> { Http.open(web, "https://nowhere.invalid/rss", sleep = {}) }
    }

    @Test
    fun `an http hop in a redirect that has no https version needs a secure link`() {
        val web = FakeWeb(
            mapOf(
                "https://tracker.com/x" to redirect(302, "http://old-host.com/ep.mp3"),
                "https://old-host.com/ep.mp3" to fails(SSLHandshakeException("no TLS")),
            ),
        )
        assertFailsWith<NetError.NeedsSecureLink> { Http.open(web, "https://tracker.com/x") }
    }

    @Test
    fun `bodies over the cap are refused`() {
        val web = FakeWeb(mapOf("https://a.com/big" to ok("0123456789")))
        assertFailsWith<NetError.TooLarge> { Http.readBytes(Http.open(web, "https://a.com/big"), 5) }
        val unknownLength = FakeWeb(mapOf("https://a.com/big" to { HttpResponse(200, null, null, -1, ByteArrayInputStream(ByteArray(50))) }))
        assertFailsWith<NetError.TooLarge> { Http.readBytes(Http.open(unknownLength, "https://a.com/big"), 10) }
        assertEquals(10, Http.readBytes(Http.open(web, "https://a.com/big"), 10).size)
    }

    @Test
    fun `a failed connection is tried once more after a pause (switching Wi-Fi to mobile data)`() {
        var calls = 0
        val slept = mutableListOf<Long>()
        val flaky = HttpTransport { _, _ ->
            calls++
            if (calls == 1) throw UnknownHostException("a.com") else ok("hi")()
        }
        val opened = Http.open(flaky, "https://a.com/feed", sleep = { slept += it })
        assertEquals("hi", Http.readText(opened, 100))
        assertEquals(2, calls)
        assertEquals(listOf(Http.RETRY_DELAY_MS), slept)

        calls = 0
        val down = HttpTransport { _, _ ->
            calls++
            throw UnknownHostException("a.com")
        }
        assertFailsWith<NetError.NoConnection> { Http.open(down, "https://a.com/feed", sleep = {}) }
        assertEquals(2, calls, "only one retry")
    }
}
