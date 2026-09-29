package com.thelightphone.listen.music

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.playlists.Playlists
import com.thelightphone.listen.playlists.PlaylistsScreen
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
        val playlists by Playlists.playlists.collectAsState()
        val playlistsLoaded by Playlists.loaded.collectAsState()
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
                MenuRow(
                    title = "Artists",
                    detail = if (library.loaded) artistCount(library.artists.size) else null,
                    onClick = ::openArtists,
                )
                MenuRow(
                    title = "Albums",
                    detail = if (library.loaded) albumCount(library.albums.size) else null,
                    onClick = ::openAlbums,
                )
                MenuRow(
                    title = "Playlists",
                    detail = if (playlistsLoaded) playlistCount(playlists.size) else null,
                    onClick = ::openPlaylists,
                )
            }
            UpdatingLine(visible = library.updating)
            NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }

    private fun openSongs() {
        navigateTo(screenFactory = { SongsScreen(it) })
    }

    private fun openArtists() {
        navigateTo(screenFactory = { ArtistsScreen(it) })
    }

    private fun openAlbums() {
        navigateTo(screenFactory = { AlbumsScreen(it) })
    }

    private fun openPlaylists() {
        navigateTo(screenFactory = { PlaylistsScreen(it) })
    }
}

/** "1 playlist", "4 playlists". */
fun playlistCount(count: Int): String = if (count == 1) "1 playlist" else "%,d playlists".format(count)

/** "1 song", "1,412 songs". */
fun songCount(count: Int): String =
    if (count == 1) "1 song" else "%,d songs".format(count)
