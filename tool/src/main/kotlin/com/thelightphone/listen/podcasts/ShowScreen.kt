package com.thelightphone.listen.podcasts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.artwork.ArtImage
import com.thelightphone.listen.artwork.ArtSize
import com.thelightphone.listen.artwork.artLetter
import com.thelightphone.listen.music.ListTopBar
import com.thelightphone.listen.music.rememberSorted
import com.thelightphone.listen.podcasts.feed.Episode
import com.thelightphone.listen.podcasts.store.EpisodeSort
import com.thelightphone.listen.podcasts.store.FeedNotes
import com.thelightphone.listen.podcasts.store.FeedSnapshot
import com.thelightphone.listen.podcasts.store.Subscription
import com.thelightphone.listen.podcasts.store.episodeKey
import com.thelightphone.listen.ui.ActionButton
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.HairlineDivider
import com.thelightphone.listen.ui.NowPlayingBar
import com.thelightphone.listen.ui.OneLine
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** The channel art at the top of a show page (grid units square). */
private const val SHOW_COVER_GRID_UNITS = 10f

/** Lines of the show's description shown before "More". */
private const val COLLAPSED_LINES = 4

/** A description longer than this (or with more lines than [COLLAPSED_LINES]) gets a "More" button. */
private const val COLLAPSE_CHARS = 200

/**
 * One show: its art, title, author and description (a few lines, "More" for the rest), then
 * every episode its feed lists, newest or oldest first (the top-right button flips it and
 * the choice is remembered for this show). Tap an episode to open it; press and hold to mark
 * it played or unplayed. Unfollow is at the end of the header.
 */
class ShowScreen(sealedActivity: SealedLightActivity, private val showId: String) : ListenScreen(sealedActivity) {

    // On the screen object, so it stays open when coming back from an episode.
    private var expanded by mutableStateOf(false)

    @Composable
    override fun Content() {
        val shows by Podcasts.shows.collectAsState()
        val loaded by Podcasts.loaded.collectAsState()
        val refreshing by Podcasts.refreshing.collectAsState()
        val states by Podcasts.states.collectAsState()
        val errors by Podcasts.feedErrors.collectAsState()
        val show = shows.firstOrNull { it.showId == showId }
        // Read again after a refresh finishes, so new episodes appear.
        val snapshot by produceState<FeedSnapshot?>(null, showId, refreshing) {
            if (!refreshing) value = Podcasts.snapshot(showId)
        }
        val notes by produceState<FeedNotes?>(null, showId, refreshing) {
            if (!refreshing) value = Podcasts.notes(showId)
        }
        val sort = show?.sort ?: EpisodeSort.NEWEST
        val sorted = rememberSorted(snapshot?.episodes.orEmpty(), sort) { sortEpisodes(it, sort) }
        ThemedScreen {
            ListTopBar(
                title = show?.title?.ifEmpty { null } ?: "Show",
                onBack = { goBack() },
                onSort = show?.let { { Podcasts.setSort(showId, if (sort == EpisodeSort.NEWEST) EpisodeSort.OLDEST else EpisodeSort.NEWEST) } },
            )
            val area = Modifier.weight(1f)
            when {
                !loaded -> Box(modifier = area)
                show == null -> CenteredMessage("You don't follow this show any more.", modifier = area)
                else -> Box(modifier = area) {
                    val episodes = sorted?.items.orEmpty()
                    LightLazyScrollView(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()),
                        // The header is taller than a row, so the scrollbar is approximate.
                        uniformItemHeightGridUnits = EPISODE_ROW_GRID_UNITS,
                    ) {
                        item(key = "header") {
                            Header(show, snapshot, notes?.show.orEmpty(), sort, errors[showId])
                        }
                        itemsIndexed(episodes, key = { _, ep -> ep.id }) { index, ep ->
                            val state = states[episodeKey(showId, ep.id)] ?: com.thelightphone.listen.podcasts.store.EpisodeState()
                            EpisodeRow(
                                episode = ep,
                                state = state,
                                showDivider = index != episodes.lastIndex,
                                onOpen = { openEpisode(ep.id) },
                                onHold = {
                                    if (state.played) Podcasts.markUnplayed(showId, ep.id) else Podcasts.markPlayed(showId, ep.id)
                                },
                            )
                        }
                    }
                }
            }
            NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }

    @Composable
    private fun Header(show: Subscription, snapshot: FeedSnapshot?, description: String, sort: EpisodeSort, error: String?) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ArtImage(
                    source = Podcasts.artSource(showId),
                    size = ArtSize.LARGE,
                    letter = artLetter(show.title),
                    modifier = Modifier.size(SHOW_COVER_GRID_UNITS.gridUnitsAsDp()),
                )
                Spacer(modifier = Modifier.width(1f.gridUnitsAsDp()))
                Column(modifier = Modifier.weight(1f)) {
                    LightText(text = show.title, variant = LightTextVariant.Subheading, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(0.25f.gridUnitsAsDp()))
                    if (show.author.isNotEmpty()) OneLine(text = show.author, variant = LightTextVariant.Copy, lighten = true)
                    snapshot?.let { OneLine(text = episodeCount(it.episodes.size), variant = LightTextVariant.Detail, lighten = true) }
                }
            }
            if (description.isNotEmpty()) {
                val long = description.length > COLLAPSE_CHARS || description.count { it == '\n' } >= COLLAPSED_LINES
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = description,
                    variant = LightTextVariant.Copy,
                    maxLines = if (expanded || !long) Int.MAX_VALUE else COLLAPSED_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
                if (long) {
                    LightText(
                        text = if (expanded) "Less" else "More",
                        variant = LightTextVariant.Copy,
                        underline = true,
                        modifier = Modifier
                            .lightClickable(onClick = { expanded = !expanded })
                            .padding(vertical = 0.5f.gridUnitsAsDp()),
                    )
                }
            }
            if (error != null) {
                Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
                LightText(text = "Couldn't update: $error", variant = LightTextVariant.Detail, lighten = true)
            }
            Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
            ActionButton("Unfollow", LightIcons.CLOSE, Modifier.fillMaxWidth()) { askUnfollow(show) }
            Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
            LightText(
                text = if (sort == EpisodeSort.NEWEST) "Newest first" else "Oldest first",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
            )
            HairlineDivider()
            if (snapshot == null) {
                LightText(
                    text = "No episodes saved yet. Tap REFRESH on the Podcasts screen.",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
                )
            }
        }
    }

    private fun openEpisode(episodeId: String) = navigateTo(screenFactory = { EpisodeScreen(it, showId, episodeId) })

    private fun askUnfollow(show: Subscription) {
        navigateTo(
            screenFactory = { UnfollowScreen(it, show.title, Podcasts.downloadCount(showId)) },
            resultCallback = { choice ->
                Podcasts.unfollow(showId, deleteDownloads = choice == UnfollowChoice.UNFOLLOW_AND_DELETE)
                goBack()
            },
        )
    }
}

/** Newest or oldest first; episodes without a date go last either way, in feed order. */
fun sortEpisodes(episodes: List<Episode>, sort: EpisodeSort): List<Episode> {
    val dated = episodes.filter { it.publishedAt != null }
    val undated = episodes.filter { it.publishedAt == null }
    val ordered = if (sort == EpisodeSort.NEWEST) dated.sortedByDescending { it.publishedAt } else dated.sortedBy { it.publishedAt }
    return ordered + undated
}

/** "1 episode", "1,204 episodes". */
fun episodeCount(count: Int): String = if (count == 1) "1 episode" else "%,d episodes".format(count)
