package com.thelightphone.listen.podcasts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.artwork.artLetter
import com.thelightphone.listen.music.ALBUM_ROW_GRID_UNITS
import com.thelightphone.listen.music.ArtAndText
import com.thelightphone.listen.music.ListTopBar
import com.thelightphone.listen.music.MusicLazyList
import com.thelightphone.listen.music.SearchBox
import com.thelightphone.listen.podcasts.net.SearchResult
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.NowPlayingBar
import com.thelightphone.listen.ui.OneLine
import com.thelightphone.listen.ui.RowIconButton
import com.thelightphone.listen.ui.StatusLine
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.UniformRow
import com.thelightphone.listen.ui.tapOrHold
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import com.thelightphone.sdk.ui.rememberLightKeyboard
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment

/**
 * Finds shows in Apple's podcast directory. Type a name and tap DONE (searching on every
 * key would go over Apple's limit of about 20 searches a minute). Each result has its art,
 * title and author, and + to follow it (✓ when followed: tap to unfollow). + at the top
 * adds a show by its feed address instead.
 */
class PodcastSearchScreen(sealedActivity: SealedLightActivity) : ListenScreen(sealedActivity) {

    // Kept on the screen (not in the composition), so the words and results are still there
    // when coming back from a show.
    private val query = TextFieldState()
    private var keyboardShown by mutableStateOf(true)
    private val keyboardKey = Any()
    /** The words last searched for ("" before the first search). */
    private var searched by mutableStateOf("")
    private var searching by mutableStateOf(false)
    private var outcome by mutableStateOf<SearchOutcome?>(null)
    /** The words [outcome] is for, so coming back to this screen doesn't search again. */
    private var outcomeFor = ""

    init {
        Podcasts.clearStatus()
    }

    @Composable
    override fun Content() {
        val shows by Podcasts.shows.collectAsState()
        val status by Podcasts.status.collectAsState()
        val typed = query.text.toString()
        LaunchedEffect(searched) {
            if (searched.isBlank() || searched == outcomeFor) return@LaunchedEffect
            searching = true
            outcome = Podcasts.search(searched)
            outcomeFor = searched
            searching = false
        }
        ThemedScreen {
            ListTopBar(
                title = "Search podcasts",
                onBack = { goBack() },
                rightButton = LightBarButton.LightIcon(icon = LightIcons.ADD, onClick = ::openAddFeed, contentDescription = "Add by feed address"),
            )
            SearchBox(typed, keyboardShown, placeholder = "Show name", onTap = { keyboardShown = true })
            val area = Modifier.weight(1f)
            val result = outcome
            when {
                searching -> CenteredMessage("Searching…", modifier = area)
                result is SearchOutcome.Failed -> CenteredMessage(result.message, modifier = area)
                result is SearchOutcome.Found && result.results.isEmpty() -> CenteredMessage("Nothing found.", modifier = area)
                result is SearchOutcome.Found -> Box(modifier = area) {
                    MusicLazyList(tag = searched, rowGridUnits = ALBUM_ROW_GRID_UNITS) {
                        itemsIndexed(result.results, key = { _, r -> r.feedUrl }) { index, r ->
                            ResultRow(r, Podcasts.followedShow(r.feedUrl, shows)?.showId, index != result.results.lastIndex)
                        }
                    }
                }
                else -> CenteredMessage(HINT, modifier = area)
            }
            StatusLine(status?.text)
            if (keyboardShown) Keyboard() else NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }

    @Composable
    private fun ResultRow(r: SearchResult, followedId: String?, showDivider: Boolean) {
        UniformRow(
            heightGridUnits = ALBUM_ROW_GRID_UNITS,
            showDivider = showDivider,
            modifier = Modifier.tapOrHold(onHold = null) { if (followedId != null) openShow(followedId) else follow(r) },
        ) {
            Row(modifier = Modifier.fillMaxWidth().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                ArtAndText(
                    art = { size -> RemoteArtImage(r.thumbUrl, artLetter(r.title), size) },
                    artGridUnits = SHOW_ART_GRID_UNITS,
                    modifier = Modifier.weight(1f),
                ) {
                    OneLine(text = r.title, variant = LightTextVariant.Copy)
                    if (r.author.isNotEmpty()) OneLine(text = r.author, variant = LightTextVariant.Detail, lighten = true)
                    OneLine(
                        text = if (followedId != null) "Following" else listOfNotNull(r.genre, r.episodeCount?.let(::episodeCount)).joinToString(" · "),
                        variant = LightTextVariant.Detail,
                        lighten = true,
                    )
                }
                if (followedId != null) {
                    RowIconButton(icon = LightIcons.ACCEPT, label = "Unfollow") { askUnfollow(followedId, r.title) }
                } else {
                    RowIconButton(icon = LightIcons.ADD, label = "Follow") { follow(r) }
                }
            }
        }
    }

    @Composable
    private fun Keyboard() {
        val keyboard = rememberLightKeyboard(
            state = query,
            keyboardOptionsFlow = rememberKeyboardOptions(),
            key = keyboardKey,
            onReturn = ::submit,
        )
        LightEmbeddedLp3Keyboard(
            viewModel = keyboard,
            additionalBottomHeight = 5f.gridUnitsAsDp(),
            bottomBar = {
                LightBottomBar(topPadding = 0.dp, items = listOf(LightBarButton.Text(text = "DONE", onClick = ::submit)))
            },
        )
    }

    /** DONE or Return: put the keyboard away and search (unless it's the same words as last time). */
    private fun submit() {
        keyboardShown = false
        val words = query.text.toString().trim()
        if (words.isNotEmpty() && words != searched) searched = words
    }

    private fun follow(r: SearchResult) {
        keyboardShown = false
        Podcasts.add(r.feedUrl)
    }

    private fun openShow(showId: String) {
        keyboardShown = false
        navigateTo(screenFactory = { ShowScreen(it, showId) })
    }

    private fun askUnfollow(showId: String, title: String) {
        navigateTo(
            screenFactory = { UnfollowScreen(it, title, Podcasts.downloadCount(showId)) },
            resultCallback = { choice -> Podcasts.unfollow(showId, deleteDownloads = choice == UnfollowChoice.UNFOLLOW_AND_DELETE) },
        )
    }

    private fun openAddFeed() {
        keyboardShown = false
        navigateTo(screenFactory = { AddFeedScreen(it) }, resultCallback = { typed -> Podcasts.add(typed) })
    }

    private companion object {
        const val HINT = "Type a show's name and tap DONE.\n\nTo add a show by its feed address, tap + at the top."
    }
}
