package com.thelightphone.listen.podcasts.chapters

import com.thelightphone.listen.podcasts.feed.FeedTime
import com.thelightphone.listen.podcasts.xml.HtmlToText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Where an episode's chapters came from, best first (PODCASTS.md feature 9). */
@Serializable
enum class ChapterSource { PODCAST_JSON, PSC, ID3 }

/** One chapter: [title] and where it starts, in ms into the episode; [url] is an optional link. */
@Serializable
data class Chapter(val title: String, val startMs: Long, val endMs: Long? = null, val url: String? = null)

/** An episode's chapters in the one format Now Playing reads (saved as `<episodeId>.chapters.json`). */
@Serializable
data class ChapterList(val source: ChapterSource, val chapters: List<Chapter>)

/** Chapter files bigger than this aren't read (PODCAST_PLAN.md §5). */
const val MAX_CHAPTERS_JSON_BYTES = 1_000_000

/** More chapters than any real episode has; the rest are ignored. */
const val MAX_CHAPTERS = 1_000

/**
 * Sorts by start, keeps one chapter per start time, names untitled chapters
 * "Chapter N", and returns null when nothing is left (no chapters means no Chapters button).
 */
fun normalizeChapters(source: ChapterSource, raw: List<Chapter>): ChapterList? {
    val sorted = raw.filter { it.startMs >= 0 }.sortedBy { it.startMs }.distinctBy { it.startMs }.take(MAX_CHAPTERS)
    if (sorted.isEmpty()) return null
    val named = sorted.mapIndexed { i, c ->
        c.copy(title = c.title.ifBlank { "Chapter ${i + 1}" })
    }
    return ChapterList(source, named)
}

private val lenientJson = Json { isLenient = true }

/**
 * Reads a Podcasting 2.0 chapters file (`podcast:chapters`, type application/json+chapters):
 * `{"version":"1.2.0","chapters":[{"startTime":0,"title":"Intro","url":"…","toc":false}]}`.
 * Chapters marked `"toc": false` are silent markers and left out. Null when the text isn't
 * a chapters file or has no chapters. Never throws.
 */
fun parsePodcastChaptersJson(text: String): ChapterList? {
    if (text.length > MAX_CHAPTERS_JSON_BYTES) return null
    val root = try {
        lenientJson.parseToJsonElement(text.removePrefix("﻿"))
    } catch (e: Exception) {
        return null
    }
    val array = when (root) {
        is JsonObject -> root["chapters"] as? JsonArray
        is JsonArray -> root
        else -> null
    } ?: return null
    val chapters = array.mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        if ((o["toc"] as? JsonPrimitive)?.booleanOrNull == false) return@mapNotNull null
        val start = (o["startTime"] as? JsonPrimitive)?.let { it.doubleOrNull ?: FeedTime.parseClock(it.contentOrNull)?.div(1000.0) }
            ?: return@mapNotNull null
        val end = (o["endTime"] as? JsonPrimitive)?.doubleOrNull
        Chapter(
            title = HtmlToText.inline((o["title"] as? JsonPrimitive)?.contentOrNull),
            startMs = (start * 1000).toLong(),
            endMs = end?.let { (it * 1000).toLong() },
            url = (o["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("http") },
        )
    }
    return normalizeChapters(ChapterSource.PODCAST_JSON, chapters)
}

/** One `<psc:chapter start="00:01:02.500" title="…" href="…"/>` as the feed parser found it. */
@Serializable
data class PscChapter(val start: String, val title: String, val href: String? = null)

/** Podlove Simple Chapters from the feed, or null when none can be read. */
fun pscChapters(raw: List<PscChapter>): ChapterList? =
    normalizeChapters(
        ChapterSource.PSC,
        raw.mapNotNull { c ->
            val start = FeedTime.parseClock(c.start) ?: return@mapNotNull null
            Chapter(title = HtmlToText.inline(c.title), startMs = start, url = c.href?.takeIf { it.startsWith("http") })
        },
    )
