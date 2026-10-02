package com.thelightphone.listen.podcasts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.artwork.ArtImage
import com.thelightphone.listen.artwork.ArtSize
import com.thelightphone.listen.artwork.artLetter
import com.thelightphone.listen.music.ALBUM_ROW_GRID_UNITS
import com.thelightphone.listen.music.ArtAndText
import com.thelightphone.listen.music.ListTopBar
import com.thelightphone.listen.music.MusicLazyList
import com.thelightphone.listen.podcasts.download.sizeText
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.ConfirmScreen
import com.thelightphone.listen.ui.NowPlayingBar
import com.thelightphone.listen.ui.OneLine
import com.thelightphone.listen.ui.RowIconButton
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.UniformRow
import com.thelightphone.listen.ui.tapOrHold
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Every downloaded podcast episode, across all shows, newest download first: show art,
 * episode title, show and size, and a bin to remove that download. The space podcasts use
 * on the phone is at the top. REMOVE ALL PLAYED (after asking) frees every played one.
 * Tapping an episode opens its page.
 */
class DownloadsScreen(sealedActivity: SealedLightActivity) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val states by Podcasts.states.collectAsState()
        val loaded by Podcasts.loaded.collectAsState()
        // Worked out again whenever a download is added or removed.
        val downloadKeys = states.filterValues { it.download != null }.keys
        val episodes by produceState<List<DownloadedEpisode>?>(null, downloadKeys, states) {
            value = Podcasts.downloadedEpisodes(states)
        }
        val storage by produceState<Long?>(null, downloadKeys) { value = Podcasts.storageBytes() }
        ThemedScreen {
            ListTopBar(title = "Downloads", onBack = { goBack() })
            LightText(
                text = storage?.let { "Podcasts use ${sizeText(it)} on the phone" } ?: " ",
                variant = LightTextVariant.Detail,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 0.5f.gridUnitsAsDp()),
            )
            val area = Modifier.weight(1f)
            val list = episodes
            when {
                !loaded || list == null -> Box(modifier = area)
                list.isEmpty() -> CenteredMessage(NOTHING_DOWNLOADED, modifier = area)
                else -> Box(modifier = area) {
                    MusicLazyList(tag = "downloads", rowGridUnits = ALBUM_ROW_GRID_UNITS) {
                        itemsIndexed(list, key = { _, d -> d.showId + "/" + d.episodeId }) { index, d ->
                            DownloadRow(d, showDivider = index != list.lastIndex)
                        }
                    }
                }
            }
            if (list?.any { it.played } == true) {
                LightBottomBar(items = listOf(LightBarButton.Text(text = "REMOVE ALL PLAYED", onClick = ::askRemovePlayed)))
            }
            NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }

    @Composable
    private fun DownloadRow(d: DownloadedEpisode, showDivider: Boolean) {
        UniformRow(
            heightGridUnits = ALBUM_ROW_GRID_UNITS,
            showDivider = showDivider,
            modifier = Modifier.tapOrHold(onHold = null) {
                navigateTo(screenFactory = { EpisodeScreen(it, d.showId, d.episodeId) })
            },
        ) {
            Row(modifier = Modifier.fillMaxWidth().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                ArtAndText(
                    art = { size -> ArtImage(Podcasts.artSource(d.showId), ArtSize.THUMB, artLetter(d.showTitle), size) },
                    artGridUnits = SHOW_ART_GRID_UNITS,
                    modifier = Modifier.weight(1f),
                ) {
                    OneLine(text = d.title, variant = LightTextVariant.Copy, lighten = d.played)
                    OneLine(text = d.showTitle, variant = LightTextVariant.Detail, lighten = true)
                    OneLine(
                        text = listOfNotNull(sizeText(d.bytes), "Played".takeIf { d.played }).joinToString(" · "),
                        variant = LightTextVariant.Detail,
                        lighten = true,
                    )
                }
                RowIconButton(icon = LightIcons.TRASH, label = "Remove download") { Podcasts.removeDownload(d.showId, d.episodeId) }
            }
        }
    }

    private fun askRemovePlayed() {
        navigateTo(
            screenFactory = {
                ConfirmScreen(it, "Downloads", "Remove the downloads of every played episode?\n\nThe episodes stay in their shows.", "REMOVE")
            },
            resultCallback = { yes -> if (yes) Podcasts.removeAllPlayedDownloads() },
        )
    }

    private companion object {
        const val NOTHING_DOWNLOADED = "No downloaded episodes.\n\nOpen an episode and tap Download to keep it on the phone."
    }
}
