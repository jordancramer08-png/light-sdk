package com.thelightphone.listen.podcasts.feed

import com.thelightphone.listen.podcasts.chapters.PscChapter
import kotlinx.serialization.Serializable

/** One `podcast:transcript` the feed offers: its address and type (e.g. "text/vtt"). */
@Serializable
data class TranscriptLink(
    val url: String,
    val type: String? = null,
    val language: String? = null,
    val rel: String? = null,
)

/**
 * One episode as the feed describes it (without its description, which is kept apart so
 * long lists load fast; see [ParsedFeed.episodeNotes]). Addresses are kept as the feed
 * wrote them (made absolute); [com.thelightphone.listen.podcasts.PodcastIds.upgradeToHttps]
 * is applied when they're fetched.
 */
@Serializable
data class Episode(
    /** Short stable hash of [guid], or of [enclosureUrl] when the feed gives no guid. */
    val id: String,
    val guid: String? = null,
    val title: String,
    /** Publish time, ms since 1970; null when the feed's date can't be read. */
    val publishedAt: Long? = null,
    /** From itunes:duration; null when missing (the player finds the length later). */
    val durationMs: Long? = null,
    val enclosureUrl: String,
    val enclosureType: String? = null,
    val enclosureBytes: Long? = null,
    val imageUrl: String? = null,
    /** `podcast:chapters` (a JSON file). */
    val chaptersUrl: String? = null,
    val chaptersType: String? = null,
    /** `psc:chapters`, written into the feed itself. */
    val pscChapters: List<PscChapter> = emptyList(),
    val transcripts: List<TranscriptLink> = emptyList(),
    /** Whether the feed has any description for it, so lists can show it without loading notes. */
    val hasNotes: Boolean = false,
) {
    /** Chapters the feed promises (a downloaded file may add ID3 chapters later). */
    val feedHasChapters: Boolean get() = chaptersUrl != null || pscChapters.isNotEmpty()
    val hasTranscript: Boolean get() = transcripts.isNotEmpty()
}

/** The show itself, as its feed describes it. */
@Serializable
data class ShowInfo(
    val title: String,
    val author: String = "",
    val artUrl: String? = null,
    val link: String? = null,
    /** `itunes:new-feed-url`: the feed says it has moved here. */
    val newFeedUrl: String? = null,
)

/**
 * Everything read from one feed. [showNotes] and [episodeNotes] (keyed by episode id) are
 * the full descriptions as readable text ("" or missing when the feed has none).
 */
data class ParsedFeed(
    val show: ShowInfo,
    val showNotes: String,
    val episodes: List<Episode>,
    val episodeNotes: Map<String, String>,
)

/** The text wasn't an RSS feed at all (an HTML page, an Atom feed, an error message…). */
class NotAFeedException(message: String) : Exception(message)
