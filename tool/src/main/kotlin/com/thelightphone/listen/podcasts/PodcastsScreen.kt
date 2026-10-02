package com.thelightphone.listen.podcasts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.thelightphone.listen.podcasts.download.sizeText
import androidx.compose.ui.Modifier
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.artwork.ArtImage
import com.thelightphone.listen.artwork.ArtSize
import com.thelightphone.listen.artwork.artLetter
import com.thelightphone.listen.music.ALBUM_ROW_GRID_UNITS
import com.thelightphone.listen.music.ArtAndText
import com.thelightphone.listen.music.MusicLazyList
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.IconTopBar
import com.thelightphone.listen.ui.TopBarIcon
import com.thelightphone.listen.ui.MenuHalf
import com.thelightphone.listen.ui.SplitMenuRow
import com.thelightphone.listen.ui.NowPlayingBar
import com.thelightphone.listen.ui.OneLine
import com.thelightphone.listen.ui.StatusLine
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.UniformRow
import com.thelightphone.listen.ui.VolumeKeyScreen
import com.thelightphone.listen.ui.tapOrHold
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens

/** The same size as album covers in Albums. */
const val SHOW_ART_GRID_UNITS = 5.6f

/**
 * Podcasts: New Episodes and Downloads at the top, then the shows Jordan follows, A–Z, with
 * their channel art. In the top bar, the refresh icon checks every feed for new episodes
 * (faded while it works, with progress at the bottom) and the magnifier searches for shows
 * (and adds one by its feed address).
 */
class PodcastsScreen(sealedActivity: SealedLightActivity) : ListenScreen(sealedActivity) {

    init {
        Podcasts.clearStatus()
    }

    @Composable
    override fun Content() {
        val shows by Podcasts.shows.collectAsState()
        val loaded by Podcasts.loaded.collectAsState()
        val status by Podcasts.status.collectAsState()
        val refreshing by Podcasts.refreshing.collectAsState()
        val newEpisodes by Podcasts.newEpisodes.collectAsState()
        val errors by Podcasts.feedErrors.collectAsState()
        val states by Podcasts.states.collectAsState()
        val downloaded = states.values.mapNotNull { it.download }
        ThemedScreen {
            IconTopBar(
                title = "Podcasts",
                onBack = { goBack() },
                icons = listOf(
                    // Faded while a refresh is running; the line at the bottom counts the shows.
                    TopBarIcon(LightIcons.REFRESH, "Refresh", enabled = loaded && shows.isNotEmpty() && !refreshing, onClick = Podcasts::refreshAll),
                    TopBarIcon(LightIcons.SEARCH, "Search", onClick = ::openSearch),
                ),
            )
            val listArea = Modifier.weight(1f)
            when {
                !loaded -> Box(modifier = listArea)
                shows.isEmpty() -> CenteredMessage(NO_SHOWS, modifier = listArea)
                else -> {
                    SplitMenuRow(
                        left = MenuHalf("New Episodes", newCount(newEpisodes.size), ::openNewEpisodes),
                        right = MenuHalf("Downloads", downloadsDetail(downloaded.size, downloaded.sumOf { it.bytes }), ::openDownloads),
                    )
                    Box(modifier = listArea) {
                        MusicLazyList(tag = "shows", rowGridUnits = ALBUM_ROW_GRID_UNITS) {
                            itemsIndexed(shows, key = { _, show -> show.showId }) { index, show ->
                                UniformRow(
                                    heightGridUnits = ALBUM_ROW_GRID_UNITS,
                                    showDivider = index != shows.lastIndex,
                                    modifier = Modifier.tapOrHold(onHold = null) { openShow(show.showId) },
                                ) {
                                    ArtAndText(
                                        art = { size -> ArtImage(Podcasts.artSource(show.showId), ArtSize.THUMB, artLetter(show.title), size) },
                                        artGridUnits = SHOW_ART_GRID_UNITS,
                                    ) {
                                        OneLine(text = show.title.ifEmpty { show.feedUrl }, variant = LightTextVariant.Copy)
                                        if (show.author.isNotEmpty()) OneLine(text = show.author, variant = LightTextVariant.Detail, lighten = true)
                                        if (show.showId in errors) OneLine(text = "Couldn't update", variant = LightTextVariant.Detail, lighten = true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            StatusLine(status?.text)
            NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }

    private fun openSearch() = navigateTo(screenFactory = { PodcastSearchScreen(it) })

    private fun openNewEpisodes() = navigateTo(screenFactory = { NewEpisodesScreen(it) })

    private fun openDownloads() = navigateTo(screenFactory = { DownloadsScreen(it) })

    private fun openShow(showId: String) = navigateTo(screenFactory = { ShowScreen(it, showId) })

    private companion object {
        const val NO_SHOWS = "No shows yet.\n\nTap the magnifier to search for shows to follow."
    }
}

/** "3 · 412 MB" (episodes and their size), "None yet". */
fun downloadsDetail(count: Int, bytes: Long): String =
    if (count == 0) "None yet" else "$count · ${sizeText(bytes)}"

/** "3 new", "None". */
fun newCount(count: Int): String = if (count == 0) "None" else "$count new"

/**
 * Types a show's feed address with the phone's keyboard. Hands back what was typed; back,
 * or nothing typed, hands back nothing.
 */
class AddFeedScreen(sealedActivity: SealedLightActivity) : VolumeKeyScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val keyboardOptions = rememberKeyboardOptions()
        val state = rememberTextFieldState("https://")
        ThemedScreen {
            LightTextInputEditor(
                title = "Feed address",
                state = state,
                keyboardOptionsFlow = keyboardOptions,
                onSubmit = { typed ->
                    val text = typed.toString().trim()
                    if (text.isEmpty() || text == "https://") goBack() else goBack(text)
                },
                onBack = { goBack() },
                modifier = Modifier.background(LightThemeTokens.colors.background),
                submitLabel = "ADD",
                singleLine = true,
                initialCaps = false,
            )
        }
    }
}
