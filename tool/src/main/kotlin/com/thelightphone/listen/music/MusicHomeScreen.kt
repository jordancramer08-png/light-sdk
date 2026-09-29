package com.thelightphone.listen.music

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.ui.MenuRow
import com.thelightphone.listen.ui.NowPlayingBar
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.UpdatingLine
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/** Music: Songs, Artists, Albums and Playlists. */
class MusicHomeScreen(sealedActivity: SealedLightActivity) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val library by MusicLibrary.state.collectAsState()
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Music"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            Column(modifier = Modifier.weight(1f)) {
                MenuRow(
                    title = "Songs",
                    detail = if (library.loaded) songCount(library.songs.size) else null,
                    onClick = ::openSongs,
                )
                MenuRow(title = "Artists", detail = LATER, onClick = null)
                MenuRow(title = "Albums", detail = LATER, onClick = null)
                MenuRow(title = "Playlists", detail = LATER, onClick = null)
            }
            UpdatingLine(visible = library.updating)
            NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }

    private fun openSongs() {
        navigateTo(screenFactory = { SongsScreen(it) })
    }

    private companion object {
        const val LATER = "Coming in a later update"
    }
}

/** "1 song", "1,412 songs". */
fun songCount(count: Int): String =
    if (count == 1) "1 song" else "%,d songs".format(count)
