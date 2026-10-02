package com.thelightphone.listen.podcasts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.music.ListTopBar
import com.thelightphone.listen.music.lengthText
import com.thelightphone.listen.podcasts.download.DownloadStatus
import com.thelightphone.listen.podcasts.download.PodcastDownloads
import com.thelightphone.listen.podcasts.download.downloadProgressText
import com.thelightphone.listen.podcasts.download.sizeText
import com.thelightphone.listen.podcasts.feed.Episode
import com.thelightphone.listen.podcasts.store.EpisodeState
import com.thelightphone.listen.podcasts.store.FeedNotes
import com.thelightphone.listen.podcasts.store.FeedSnapshot
import com.thelightphone.listen.podcasts.store.episodeKey
import com.thelightphone.listen.ui.ActionButton
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.HairlineDivider
import com.thelightphone.listen.ui.NowPlayingBar
import com.thelightphone.listen.ui.OneLine
import com.thelightphone.listen.ui.StatusLine
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * One episode: title, show, date and length, where Jordan is in it, "Has chapters" / "Has
 * transcript" when the feed offers them, Play (or Stream when not downloaded), the download controls,
 * Transcript, Mark played / unplayed, then the full show notes as plain text. Long notes are laid out a paragraph at a time, only as they scroll into view.
 */
class EpisodeScreen(
    sealedActivity: SealedLightActivity,
    private val showId: String,
    private val episodeId: String,
) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val shows by Podcasts.shows.collectAsState()
        val states by Podcasts.states.collectAsState()
        val downloads by PodcastDownloads.status.collectAsState()
        val status by Podcasts.status.collectAsState()
        val show = shows.firstOrNull { it.showId == showId }
        val snapshot by produceState<FeedSnapshot?>(null, showId) { value = Podcasts.snapshot(showId) }
        val notes by produceState<FeedNotes?>(null, showId) { value = Podcasts.notes(showId) }
        val episode = snapshot?.episodes?.firstOrNull { it.id == episodeId }
        val state = states[episodeKey(showId, episodeId)] ?: EpisodeState()
        val paragraphs = notes?.episodes?.get(episodeId).orEmpty().split("\n\n").filter { it.isNotBlank() }
        ThemedScreen {
            ListTopBar(title = show?.title?.ifEmpty { null } ?: "Episode", onBack = { goBack() })
            val area = Modifier.weight(1f)
            when {
                snapshot == null -> Box(modifier = area)
                episode == null -> CenteredMessage("This episode isn't in the feed any more.", modifier = area)
                else -> Box(modifier = area) {
                    LightLazyScrollView(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()),
                        // Paragraphs differ in height, so the scrollbar is approximate.
                        uniformItemHeightGridUnits = 4f,
                    ) {
                        item(key = "header") { Header(episode, show?.title.orEmpty(), state, downloads[episodeKey(showId, episodeId)]) }
                        itemsIndexed(paragraphs, key = { i, _ -> "p$i" }) { _, paragraph ->
                            LightText(
                                text = paragraph,
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 1f.gridUnitsAsDp()),
                            )
                        }
                    }
                }
            }
            StatusLine(status?.text)
            NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }

    @Composable
    private fun Header(episode: Episode, showTitle: String, state: EpisodeState, download: DownloadStatus?) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LightText(text = episode.title, variant = LightTextVariant.Subheading, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(0.25f.gridUnitsAsDp()))
            if (showTitle.isNotEmpty()) OneLine(text = showTitle, variant = LightTextVariant.Copy, lighten = true)
            val facts = listOf(episodeDate(episode.publishedAt), episode.durationMs?.let(::lengthText).orEmpty())
                .filter { it.isNotEmpty() }.joinToString(" · ")
            if (facts.isNotEmpty()) OneLine(text = facts, variant = LightTextVariant.Detail, lighten = true)
            progressText(state, episode.durationMs).takeIf { it.isNotEmpty() }?.let {
                OneLine(text = it, variant = LightTextVariant.Detail, lighten = true)
            }
            val labels = listOfNotNull(
                "Has chapters".takeIf { episode.feedHasChapters },
                "Has transcript".takeIf { episode.hasTranscript },
            )
            if (labels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
                OneLine(text = labels.joinToString("  ·  "), variant = LightTextVariant.Detail)
            }
            Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
            val resume = state.positionMs > 0 && !state.played
            val playLabel = when {
                state.download != null -> if (resume) "Resume" else "Play"
                else -> if (resume) "Resume (stream)" else "Stream"
            }
            ActionButton(playLabel, LightIcons.PLAY, Modifier.fillMaxWidth()) {
                Podcasts.play(showId, episodeId) { openNowPlaying() }
            }
            Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
            DownloadControls(episode, state, download)
            if (episode.hasTranscript || state.download?.transcript != null) {
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                ActionButton("Transcript", LightIcons.LIST, Modifier.fillMaxWidth()) {
                    navigateTo(screenFactory = { TranscriptScreen(it, showId, episodeId) })
                }
            }
            Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
            if (state.played) {
                ActionButton("Mark unplayed", LightIcons.CLOSE, Modifier.fillMaxWidth()) { Podcasts.markUnplayed(showId, episodeId) }
            } else {
                ActionButton("Mark played", LightIcons.ACCEPT, Modifier.fillMaxWidth()) { Podcasts.markPlayed(showId, episodeId) }
            }
            Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
            HairlineDivider()
            Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
        }
    }

    /**
     * Download (with its size when the feed says), then progress and Cancel while it runs,
     * the reason and Retry if it failed, and Remove download once it's on the phone.
     */
    @Composable
    private fun DownloadControls(episode: Episode, state: EpisodeState, download: DownloadStatus?) {
        val files = state.download
        when {
            files != null -> {
                OneLine(text = "Downloaded · ${sizeText(files.bytes)}", variant = LightTextVariant.Detail, lighten = true)
                Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
                ActionButton("Remove download", LightIcons.TRASH, Modifier.fillMaxWidth()) { Podcasts.removeDownload(showId, episodeId) }
            }
            download is DownloadStatus.Active -> {
                OneLine(text = downloadProgressText(download), variant = LightTextVariant.Detail)
                Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
                ActionButton("Cancel download", LightIcons.CLOSE, Modifier.fillMaxWidth()) {
                    PodcastDownloads.cancel(lightContext, showId, episodeId)
                }
            }
            download is DownloadStatus.Failed -> {
                LightText(text = download.message, variant = LightTextVariant.Detail)
                Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
                ActionButton("Retry", LightIcons.REFRESH, Modifier.fillMaxWidth()) {
                    Podcasts.clearStatus()
                    PodcastDownloads.start(lightContext, showId, episodeId)
                }
            }
            else -> {
                val size = episode.enclosureBytes?.let { " (${sizeText(it)})" }.orEmpty()
                ActionButton("Download$size", LightIcons.DOWNLOAD_ARROW, Modifier.fillMaxWidth()) {
                    Podcasts.clearStatus()
                    PodcastDownloads.start(lightContext, showId, episodeId)
                }
            }
        }
    }
}
