package com.thelightphone.listen.podcasts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.artwork.ArtImage
import com.thelightphone.listen.artwork.ArtSize
import com.thelightphone.listen.artwork.artLetter
import com.thelightphone.listen.music.ALBUM_ROW_GRID_UNITS
import com.thelightphone.listen.music.ArtAndText
import com.thelightphone.listen.music.ListTopBar
import com.thelightphone.listen.music.MusicLazyList
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.NowPlayingBar
import com.thelightphone.listen.ui.OneLine
import com.thelightphone.listen.ui.StatusLine
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.UniformRow
import com.thelightphone.listen.ui.VolumeKeyScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * The shows Jordan follows, A–Z, each with its channel art. + adds one by its feed address
 * (the PC's Podcasts.cmd is the easier way). Show pages come in a later session.
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
        ThemedScreen {
            ListTopBar(
                title = "Podcasts",
                onBack = { goBack() },
                rightButton = LightBarButton.LightIcon(
                    icon = LightIcons.ADD,
                    onClick = ::openAddFeed,
                    contentDescription = "Add a show",
                ),
            )
            val listArea = Modifier.weight(1f)
            when {
                !loaded -> Box(modifier = listArea)
                shows.isEmpty() -> CenteredMessage(NO_SHOWS, modifier = listArea)
                else -> Box(modifier = listArea) {
                    MusicLazyList(tag = "shows", rowGridUnits = ALBUM_ROW_GRID_UNITS) {
                        itemsIndexed(shows, key = { _, show -> show.showId }) { index, show ->
                            UniformRow(heightGridUnits = ALBUM_ROW_GRID_UNITS, showDivider = index != shows.lastIndex) {
                                ArtAndText(
                                    art = { size ->
                                        ArtImage(Podcasts.artSource(show.showId), ArtSize.THUMB, artLetter(show.title), size)
                                    },
                                    artGridUnits = SHOW_ART_GRID_UNITS,
                                ) {
                                    OneLine(text = show.title.ifEmpty { show.feedUrl }, variant = LightTextVariant.Copy)
                                    if (show.author.isNotEmpty()) {
                                        OneLine(text = show.author, variant = LightTextVariant.Detail, lighten = true)
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

    private fun openAddFeed() {
        navigateTo(
            screenFactory = { AddFeedScreen(it) },
            resultCallback = { typed -> Podcasts.add(typed) },
        )
    }

    private companion object {
        /** The same size as album covers in Albums. */
        const val SHOW_ART_GRID_UNITS = 5.6f

        const val NO_SHOWS = "No shows yet.\n\nTap + to add one by its feed address, " +
            "or follow shows with Podcasts.cmd on the PC."
    }
}

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
