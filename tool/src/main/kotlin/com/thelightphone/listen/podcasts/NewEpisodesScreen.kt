package com.thelightphone.listen.podcasts

import com.thelightphone.listen.ListenScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.listen.artwork.ArtImage
import com.thelightphone.listen.artwork.ArtSize
import com.thelightphone.listen.artwork.artLetter
import com.thelightphone.listen.music.ALBUM_ROW_GRID_UNITS
import com.thelightphone.listen.music.ArtAndText
import com.thelightphone.listen.music.ListTopBar
import com.thelightphone.listen.music.MusicLazyList
import com.thelightphone.listen.podcasts.store.EpisodeState
import com.thelightphone.listen.podcasts.store.episodeKey
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.NowPlayingBar
import com.thelightphone.listen.ui.OneLine
import com.thelightphone.listen.ui.StatusLine
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.UniformRow
import com.thelightphone.listen.ui.tapOrHold
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightTextVariant

/**
 * New Episodes: unplayed episodes from every followed show, from the last 30 days and since
 * each show was followed, newest first (it stands in for notifications, which tools can't
 * send). Tap one to open it; press and hold to mark it played. MARK ALL PLAYED clears the list.
 */
class NewEpisodesScreen(sealedActivity: SealedLightActivity) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val episodes by Podcasts.newEpisodes.collectAsState()
        val states by Podcasts.states.collectAsState()
        val loaded by Podcasts.loaded.collectAsState()
        val status by Podcasts.status.collectAsState()
        ThemedScreen {
            ListTopBar(title = "New Episodes", onBack = { goBack() })
            val area = Modifier.weight(1f)
            when {
                !loaded -> Box(modifier = area)
                episodes.isEmpty() -> CenteredMessage(NOTHING_NEW, modifier = area)
                else -> Box(modifier = area) {
                    MusicLazyList(tag = "new", rowGridUnits = ALBUM_ROW_GRID_UNITS) {
                        itemsIndexed(episodes, key = { _, n -> n.show.showId + "/" + n.episode.id }) { index, n ->
                            val state = states[episodeKey(n.show.showId, n.episode.id)] ?: EpisodeState()
                            UniformRow(
                                heightGridUnits = ALBUM_ROW_GRID_UNITS,
                                showDivider = index != episodes.lastIndex,
                                modifier = Modifier.tapOrHold(onHold = { Podcasts.markPlayed(n.show.showId, n.episode.id) }) {
                                    navigateTo(screenFactory = { EpisodeScreen(it, n.show.showId, n.episode.id) })
                                },
                            ) {
                                ArtAndText(
                                    art = { size -> ArtImage(Podcasts.artSource(n.show.showId), ArtSize.THUMB, artLetter(n.show.title), size) },
                                    artGridUnits = SHOW_ART_GRID_UNITS,
                                ) {
                                    OneLine(text = n.episode.title, variant = LightTextVariant.Copy)
                                    OneLine(text = n.show.title, variant = LightTextVariant.Detail, lighten = true)
                                    OneLine(text = episodeDetail(n.episode, state), variant = LightTextVariant.Detail, lighten = true)
                                }
                            }
                        }
                    }
                }
            }
            StatusLine(status?.text?.takeIf { status?.working == true })
            if (episodes.isNotEmpty()) {
                LightBottomBar(items = listOf(LightBarButton.Text(text = "MARK ALL PLAYED", onClick = Podcasts::markAllNewPlayed)))
            }
            NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }

    private companion object {
        const val NOTHING_NEW = "No new episodes.\n\nTap REFRESH on the Podcasts screen to check for new ones."
    }
}
