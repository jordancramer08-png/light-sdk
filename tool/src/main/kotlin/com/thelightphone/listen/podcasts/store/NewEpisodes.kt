package com.thelightphone.listen.podcasts.store

import com.thelightphone.listen.podcasts.feed.Episode

/** One row of New Episodes: the show it's from and the episode. */
data class NewEpisode(val show: Subscription, val episode: Episode)

/** How far back New Episodes looks (PODCASTS.md feature 3). */
const val NEW_EPISODES_WINDOW_DAYS = 30

/**
 * The New Episodes list: from followed shows, unplayed, published in the last [windowDays]
 * days **and** after the show was followed (so following a show doesn't flood the list with
 * its back catalogue), newest first. Episodes without a readable date are left out, since
 * their age can't be told.
 *
 * [episodesByShow] holds each show's episodes as last fetched (feed.json), keyed by show id.
 */
fun newEpisodes(
    shows: List<Subscription>,
    episodesByShow: Map<String, List<Episode>>,
    states: Map<String, EpisodeState>,
    now: Long,
    windowDays: Int = NEW_EPISODES_WINDOW_DAYS,
): List<NewEpisode> {
    val since = now - windowDays * DAY_MS
    return shows.asSequence()
        .filter { it.following }
        .flatMap { show ->
            episodesByShow[show.showId].orEmpty().asSequence()
                .filter { ep ->
                    val published = ep.publishedAt ?: return@filter false
                    published >= since && published >= show.followedAt &&
                        states[episodeKey(show.showId, ep.id)]?.played != true
                }
                .map { NewEpisode(show, it) }
        }
        .sortedWith(compareByDescending<NewEpisode> { it.episode.publishedAt }.thenBy { it.episode.title })
        .toList()
}

private const val DAY_MS = 24L * 60 * 60 * 1000
