package com.thelightphone.listen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.listen.books.BookLibrary
import com.thelightphone.listen.books.BooksScreen
import com.thelightphone.listen.books.bookCount
import com.thelightphone.listen.music.MusicHomeScreen
import com.thelightphone.listen.music.MusicLibrary
import com.thelightphone.listen.playback.PlaybackHub
import com.thelightphone.listen.podcasts.Podcasts
import com.thelightphone.listen.podcasts.PodcastsScreen
import com.thelightphone.listen.podcasts.showCount
import com.thelightphone.listen.settings.SettingsScreen
import com.thelightphone.listen.storage.StorageAccess
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.MenuRow
import com.thelightphone.listen.ui.NowPlayingBar
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.UpdatingLine
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Listen's first screen: Music, Audiobooks, Podcasts and Now Playing. Without All files access it
 * shows how to grant it instead, and checks again each time Listen comes back to the front.
 */
@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : ListenScreen(sealedActivity) {

    /** Null until checked, then whether Listen can read /sdcard/Listen. */
    private val hasAccess = MutableStateFlow<Boolean?>(null)

    override fun willShow() {
        super.willShow()
        hasAccess.value = StorageAccess.hasAllFilesAccess()
    }

    @Composable
    override fun Content() {
        val access by hasAccess.collectAsState()
        val library by MusicLibrary.state.collectAsState()
        val song by PlaybackHub.currentSong.collectAsState()
        val book by PlaybackHub.book.collectAsState()
        val books by BookLibrary.state.collectAsState()
        val shows by Podcasts.shows.collectAsState()
        val showsLoaded by Podcasts.loaded.collectAsState()
        val newEpisodes by Podcasts.newEpisodes.collectAsState()
        ThemedScreen {
            LightTopBar(
                center = LightTopBarCenter.Text("Listen"),
                rightButton = if (access == true) {
                    LightBarButton.LightIcon(icon = LightIcons.SETTINGS, onClick = ::openSettings, contentDescription = "Settings")
                } else {
                    null
                },
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            when (access) {
                null -> Box(modifier = Modifier.weight(1f))
                false -> CenteredMessage(NO_ACCESS_MESSAGE, modifier = Modifier.weight(1f))
                true -> {
                    // Scrolls rather than squashing a row when they don't all fit.
                    LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        MenuRow(title = "Music", onClick = ::openMusic)
                        MenuRow(
                            title = "Audiobooks",
                            detail = if (books.loaded) bookCount(books.books.size) else null,
                            onClick = ::openAudiobooks,
                        )
                        MenuRow(
                            title = "Podcasts",
                            detail = when {
                                !showsLoaded -> null
                                newEpisodes.isEmpty() -> showCount(shows.size)
                                else -> showCount(shows.size) + " · " + newEpisodes.size + " new"
                            },
                            onClick = ::openPodcasts,
                        )
                        MenuRow(
                            title = "Now Playing",
                            detail = book?.let { "${it.title} · ${it.author}" }
                                ?: song?.let { "${it.title} · ${it.artist}" }
                                ?: "Nothing playing",
                            onClick = ::openNowPlaying,
                        )
                    }
                    UpdatingLine(visible = library.updating || books.updating)
                    NowPlayingBar(onOpen = ::openNowPlaying)
                }
            }
        }
    }

    private fun openAudiobooks() {
        navigateTo(screenFactory = { BooksScreen(it) })
    }

    private fun openPodcasts() {
        navigateTo(screenFactory = { PodcastsScreen(it) })
    }

    private fun openSettings() {
        navigateTo(screenFactory = { SettingsScreen(it) })
    }

    private fun openMusic() {
        navigateTo(screenFactory = { MusicHomeScreen(it) })
    }

    private companion object {
        const val NO_ACCESS_MESSAGE =
            "Listen can't see its files yet.\n\n" +
                "Run Listen-Phone-Sync.cmd on the PC and choose option 9.\n\n" +
                "Then close Listen and open it again."
    }
}
