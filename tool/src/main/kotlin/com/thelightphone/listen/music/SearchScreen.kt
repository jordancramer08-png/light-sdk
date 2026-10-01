package com.thelightphone.listen.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.playback.PlaybackHub
import com.thelightphone.listen.playback.QueueSource
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.NowPlayingBar
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.sdk.ui.rememberLightKeyboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Music search: a box with the phone's keyboard, and as you type the matching Songs, Artists
 * and Albums under it, from the index already in memory. DONE (or Return) puts the keyboard
 * away so the results fill the screen; tapping the box brings it back. Back, or emptying the
 * box, closes search.
 */
class SearchScreen(sealedActivity: SealedLightActivity) : ListenScreen(sealedActivity) {

    // Kept on the screen (not in the composition) so the words and results are still there
    // when you come back from an album or artist.
    private val query = TextFieldState()
    private var keyboardShown by mutableStateOf(true)
    private val keyboardKey = Any()

    @Composable
    override fun Content() {
        val library by MusicLibrary.state.collectAsState()
        val typed = query.text.toString()
        val results by produceState(SearchResults(), library, typed) {
            value = withContext(Dispatchers.Default) { library.searchIndex.search(typed) }
        }
        // Emptying the box closes search (it opens empty, so only after something was typed).
        LaunchedEffect(Unit) {
            var hadText = query.text.isNotEmpty()
            snapshotFlow { query.text.isNotEmpty() }.collect { hasText ->
                if (hadText && !hasText) goBack()
                hadText = hasText
            }
        }
        ThemedScreen {
            ListTopBar(title = "Search", onBack = { goBack() })
            SearchBox(typed, keyboardShown, onTap = { keyboardShown = true })
            val listArea = Modifier.weight(1f)
            when {
                typed.isBlank() -> Box(modifier = listArea)
                results.isEmpty -> CenteredMessage("Nothing found.", modifier = listArea)
                else -> Box(modifier = listArea) {
                    // Rows differ in height (songs, albums, headings), so the scrollbar is approximate.
                    LightLazyScrollView(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()),
                        uniformItemHeightGridUnits = SONG_ROW_GRID_UNITS,
                    ) {
                        resultRows(results, library)
                    }
                }
            }
            if (keyboardShown) {
                Keyboard()
            } else {
                NowPlayingBar(onOpen = ::openNowPlaying)
            }
        }
    }

    @Composable
    private fun Keyboard() {
        val keyboard = rememberLightKeyboard(
            state = query,
            keyboardOptionsFlow = rememberKeyboardOptions(),
            key = keyboardKey,
            onReturn = { keyboardShown = false },
        )
        LightEmbeddedLp3Keyboard(
            viewModel = keyboard,
            additionalBottomHeight = 5f.gridUnitsAsDp(),
            bottomBar = {
                LightBottomBar(
                    topPadding = 0.dp,
                    items = listOf(LightBarButton.Text(text = "DONE", onClick = { keyboardShown = false })),
                )
            },
        )
    }

    private fun LazyListScope.resultRows(results: SearchResults, library: MusicLibraryState) {
        if (results.songs.isNotEmpty()) {
            item(key = "heading:songs") { Heading("Songs") }
            songRows(results.songs, library, onPlay = { songs, index -> play(songs[index], library) }, onHold = { addToPlaylist(it) })
        }
        if (results.artists.isNotEmpty()) {
            item(key = "heading:artists") { Heading("Artists") }
            artistRows(results.artists, onOpen = { leaveFor { openArtist(it) } }, onHold = { addToPlaylist(it) })
        }
        if (results.albums.isNotEmpty()) {
            item(key = "heading:albums") { Heading("Albums") }
            albumRows(results.albums, onOpen = { leaveFor { openAlbum(it) } }, onHold = { addToPlaylist(it) })
        }
    }

    /** Plays [song], then the rest of its album (gapless, in track order). */
    private fun play(song: Song, library: MusicLibraryState) {
        val album = library.albumOf(song)
        val index = album?.songs?.indexOfFirst { it.path == song.path } ?: -1
        if (album != null && index >= 0) {
            PlaybackHub.playSongs(album.songs, index, QueueSource(QueueSource.KIND_ALBUM, album.key))
        } else {
            PlaybackHub.playSongs(listOf(song), 0, QueueSource())
        }
        leaveFor { openNowPlaying() }
    }

    /** Opens another screen with the keyboard put away, so the results are all visible on the way back. */
    private fun leaveFor(open: () -> Unit) {
        keyboardShown = false
        open()
    }
}

private const val UNDERLINE_THICKNESS_PX = 3f

/** The words typed so far on an underlined line, with a cursor while the keyboard is up. Tap to type. */
@Composable
private fun SearchBox(typed: String, keyboardShown: Boolean, onTap: () -> Unit) {
    val colors = LightThemeTokens.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp())
            .padding(bottom = 0.75f.gridUnitsAsDp())
            .lightClickable(onClickLabel = "Type", onClick = onTap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (typed.isEmpty()) {
                if (keyboardShown) Cursor()
                LightText(
                    text = "Songs, artists, albums",
                    variant = LightTextVariant.Subheading,
                    lighten = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                LightText(
                    text = typed,
                    variant = LightTextVariant.Subheading,
                    maxLines = 1,
                    // A long search keeps its end, where you're typing, in view.
                    overflow = TextOverflow.StartEllipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (keyboardShown) Cursor()
            }
        }
        Spacer(modifier = Modifier.height(0.4f.gridUnitsAsDp()))
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(UNDERLINE_THICKNESS_PX.designVerticalPxToDp())
                .background(colors.content),
        )
    }
}

@Composable
private fun Cursor() {
    Box(
        modifier = Modifier
            .width(2.dp)
            .height(1.6f.gridUnitsAsDp())
            .background(LightThemeTokens.colors.content),
    )
}

/** A group heading in the results: Songs, Artists, Albums. */
@Composable
private fun Heading(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = true,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 0.75f.gridUnitsAsDp(), bottom = 0.25f.gridUnitsAsDp()),
    )
}
