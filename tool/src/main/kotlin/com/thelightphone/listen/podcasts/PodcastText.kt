package com.thelightphone.listen.podcasts

import com.thelightphone.listen.music.lengthText
import com.thelightphone.listen.podcasts.feed.Episode
import com.thelightphone.listen.podcasts.store.EpisodeState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val MONTH_DAY_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

/** "Today", "Yesterday", "Sep 29" (this year), "Sep 29, 2025" (another year), or "" without a date. */
fun episodeDate(publishedAt: Long?, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (publishedAt == null) return ""
    val day = Instant.ofEpochMilli(publishedAt).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.year == today.year -> day.format(MONTH_DAY)
        else -> day.format(MONTH_DAY_YEAR)
    }
}

/** Where Jordan is in an episode: "Played", "12 min left", or "" when it hasn't been started. */
fun progressText(state: EpisodeState, durationMs: Long?): String {
    if (state.played) return "Played"
    if (state.positionMs <= 0) return ""
    val length = durationMs?.takeIf { it > 0 } ?: state.durationMs
    return if (length > state.positionMs) "${lengthText(length - state.positionMs)} left" else "Started"
}

/** An episode row's second line: "Sep 29 · 1 hr 2 min · 12 min left". */
fun episodeDetail(episode: Episode, state: EpisodeState, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String =
    listOf(
        episodeDate(episode.publishedAt, now, zone),
        episode.durationMs?.let(::lengthText).orEmpty(),
        progressText(state, episode.durationMs),
    ).filter { it.isNotEmpty() }.joinToString(" · ")
