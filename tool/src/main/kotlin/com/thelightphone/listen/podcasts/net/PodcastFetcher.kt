package com.thelightphone.listen.podcasts.net

import com.thelightphone.listen.podcasts.chapters.ChapterList
import com.thelightphone.listen.podcasts.chapters.MAX_CHAPTERS_JSON_BYTES
import com.thelightphone.listen.podcasts.chapters.parsePodcastChaptersJson
import com.thelightphone.listen.podcasts.feed.FeedParser
import com.thelightphone.listen.podcasts.feed.ParsedFeed
import com.thelightphone.listen.podcasts.transcripts.MAX_TRANSCRIPT_BYTES
import com.thelightphone.listen.podcasts.transcripts.Transcript
import com.thelightphone.listen.podcasts.transcripts.TranscriptFormat
import com.thelightphone.listen.podcasts.transcripts.Transcripts

/** A feed as fetched: where it really lives now, and what it says. */
class FetchedFeed(
    /** The address that finally answered (after redirects, upgraded to https). */
    val finalUrl: String,
    /**
     * Set when the feed has moved for good: a permanent redirect on the first hop, or the
     * feed's own `itunes:new-feed-url`. The subscription should remember this address.
     */
    val movedTo: String?,
    val feed: ParsedFeed,
)

/**
 * Fetches feeds, chapter files and transcripts, each with a size cap. Blocking: call from a
 * background thread (Dispatchers.IO).
 */
class PodcastFetcher(private val transport: HttpTransport = OkHttpTransport()) {

    /** Reads [feedUrl] and parses it as it streams in (the feed is never held whole). */
    fun fetchFeed(feedUrl: String): FetchedFeed {
        val opened = Http.open(transport, feedUrl, mapOf("Accept" to FEED_ACCEPT))
        val feed = opened.use {
            if (it.response.contentLength > MAX_FEED_BYTES) throw NetError.TooLarge(MAX_FEED_BYTES)
            FeedParser.parse(CappedInputStream(it.response.body, MAX_FEED_BYTES), it.url)
        }
        val declaredMove = feed.show.newFeedUrl?.takeIf { it != feedUrl && it != opened.url }
        return FetchedFeed(opened.url, opened.movedTo ?: declaredMove, feed)
    }

    /** A `podcast:chapters` JSON file, or null when it holds no chapters. */
    fun fetchChapters(url: String): ChapterList? {
        val text = Http.readText(Http.open(transport, url), MAX_CHAPTERS_JSON_BYTES.toLong())
        return parsePodcastChaptersJson(text)
    }

    /** A transcript's raw text (saved as downloaded, so it reads offline). */
    fun fetchTranscriptText(url: String): String =
        Http.readText(Http.open(transport, url), MAX_TRANSCRIPT_BYTES.toLong())

    /** A transcript read into lines, or null when it isn't a format Listen reads. */
    fun fetchTranscript(url: String, type: String?): Transcript? {
        val format: TranscriptFormat = Transcripts.formatOf(type, url) ?: return null
        return Transcripts.parse(format, fetchTranscriptText(url))
    }

    /**
     * Where an episode's audio really is: follows the tracking redirects (upgrading each hop
     * to https) with a tiny 2-byte request, and returns the final secure address for the
     * player, which can't follow an https-to-http redirect itself.
     */
    fun resolveStreamUrl(url: String): String =
        Http.open(transport, url, mapOf("Range" to "bytes=0-1")).use { it.url }

    /** Channel art as downloaded (shrunk before saving; see ArtworkCache.saveScaled). */
    fun fetchArt(url: String): ByteArray = Http.readBytes(Http.open(transport, url), MAX_ART_BYTES)

    companion object {
        /** Art bigger than this isn't downloaded (a 3,000 px JPEG is usually 1–3 MB). */
        const val MAX_ART_BYTES = 15L * 1024 * 1024

        /** Biggest feed read (PODCAST_PLAN.md §5). Big shows' feeds are usually 1–10 MB. */
        const val MAX_FEED_BYTES = 20L * 1024 * 1024

        private const val FEED_ACCEPT =
            "application/rss+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.5"
    }
}
