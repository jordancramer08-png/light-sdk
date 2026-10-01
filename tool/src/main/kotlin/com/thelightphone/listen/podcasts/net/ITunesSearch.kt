package com.thelightphone.listen.podcasts.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

/** One show found by search: what a result row shows, and [feedUrl] to follow it. */
data class SearchResult(
    val title: String,
    val author: String,
    val feedUrl: String,
    val artUrl: String?,
    val appleId: Long?,
    val episodeCount: Int?,
    val genre: String?,
)

/**
 * Apple's free podcast directory search (no key): `https://itunes.apple.com/search?
 * media=podcast&entity=podcast&term=…`. Apple asks for about 20 calls a minute at most, so
 * [SearchLimiter] keeps Listen under that, and the screen should search on DONE, not on
 * every key.
 */
class ITunesSearch(
    private val transport: HttpTransport = OkHttpTransport(),
    private val limiter: SearchLimiter = SearchLimiter(),
) {
    /** Blocking; call off the main thread. Throws [SearchLimiter.TooSoon] when over the limit. */
    fun search(term: String, limit: Int = 25): List<SearchResult> {
        val q = term.trim()
        if (q.isEmpty()) return emptyList()
        limiter.acquire()
        val text = Http.readText(Http.open(transport, searchUrl(q, limit)), MAX_RESPONSE_BYTES)
        return parseResults(text)
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024

        fun searchUrl(term: String, limit: Int): String =
            "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=${limit.coerceIn(1, 200)}&term=" +
                URLEncoder.encode(term, "UTF-8")

        private val json = Json { isLenient = true }

        /** The shows in an iTunes Search response. Results without a feed can't be followed and are left out. */
        fun parseResults(text: String): List<SearchResult> {
            val root = try {
                json.parseToJsonElement(text) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: return emptyList()
            val results = root["results"] as? JsonArray ?: return emptyList()
            return results.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                fun s(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                val feed = s("feedUrl") ?: return@mapNotNull null
                SearchResult(
                    title = s("collectionName") ?: s("trackName") ?: return@mapNotNull null,
                    author = s("artistName").orEmpty(),
                    feedUrl = feed,
                    artUrl = s("artworkUrl600") ?: s("artworkUrl100") ?: s("artworkUrl60"),
                    appleId = (o["collectionId"] as? JsonPrimitive)?.longOrNull,
                    episodeCount = (o["trackCount"] as? JsonPrimitive)?.intOrNull,
                    genre = s("primaryGenreName"),
                )
            }.distinctBy { it.feedUrl }
        }
    }
}

/** Allows at most [maxCalls] searches in any [windowMs]; the clock is swappable for tests. */
class SearchLimiter(
    private val maxCalls: Int = 20,
    private val windowMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val calls = ArrayDeque<Long>()

    class TooSoon(val waitMs: Long) : NetError("Too many searches. Wait a moment and try again.")

    @Synchronized
    fun acquire() {
        val now = clock()
        while (calls.isNotEmpty() && now - calls.first() >= windowMs) calls.removeFirst()
        if (calls.size >= maxCalls) throw TooSoon(windowMs - (now - calls.first()))
        calls.addLast(now)
    }
}
