package com.thelightphone.listen.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.playback.PlaybackHub
import com.thelightphone.listen.playback.QueueSource
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.NowPlayingBar
import com.thelightphone.listen.ui.OneLine
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.UniformRow
import com.thelightphone.listen.ui.UpdatingLine
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.lightClickable

/** An artist: their albums with covers (by year, then title), then "All songs". */
class ArtistScreen(sealedActivity: SealedLightActivity, private val artistKey: String) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val library by MusicLibrary.state.collectAsState()
        val artist = library.artist(artistKey)
        ThemedScreen {
            ListTopBar(
                title = artist?.name ?: "Artist",
                onBack = { goBack() },
                onAddToPlaylist = artist?.let { { addToPlaylist(it) } },
            )
            val listArea = Modifier.weight(1f)
            when {
                !library.loaded -> Box(modifier = listArea)
                artist == null -> CenteredMessage(NOT_ON_PHONE, modifier = listArea)
                else -> Box(modifier = listArea) {
                    MusicLazyList(tag = artist.key, rowGridUnits = ALBUM_ROW_GRID_UNITS) {
                        albumRows(artist.albums, onOpen = { openAlbum(it) }, onHold = { addToPlaylist(it) }, lastHasDivider = true)
                        item(key = "all-songs") {
                            UniformRow(
                                heightGridUnits = ALBUM_ROW_GRID_UNITS,
                                showDivider = false,
                                modifier = Modifier.lightClickable { openAllSongs(artist) },
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    OneLine(text = "All songs", variant = LightTextVariant.Copy)
                                    OneLine(text = songCount(artist.songCount), variant = LightTextVariant.Detail, lighten = true)
                                }
                            }
                        }
                    }
                }
            }
            UpdatingLine(visible = library.updating)
            NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }

    private fun openAllSongs(artist: Artist) {
        navigateTo(screenFactory = { ArtistSongsScreen(it, artist.key) })
    }

    private companion object {
        const val NOT_ON_PHONE = "This artist isn't on the phone any more."
    }
}

/**
 * All of an artist's songs, album by album (by year), each album in track order, with the
 * album name under each title. Tapping a song plays them all from there.
 */
class ArtistSongsScreen(sealedActivity: SealedLightActivity, private val artistKey: String) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val library by MusicLibrary.state.collectAsState()
        val artist = library.artist(artistKey)
        ThemedScreen {
            ListTopBar(
                title = artist?.name ?: "Artist",
                onBack = { goBack() },
                onAddToPlaylist = artist?.let { { addToPlaylist(it) } },
            )
            val listArea = Modifier.weight(1f)
            when {
                !library.loaded -> Box(modifier = listArea)
                artist == null -> CenteredMessage("This artist isn't on the phone any more.", modifier = listArea)
                else -> Box(modifier = listArea) {
                    MusicLazyList(tag = artist.key, rowGridUnits = SONG_ROW_GRID_UNITS) {
                        songRows(
                            artist.songs,
                            library,
                            onPlay = { songs, index -> play(artist, songs, index) },
                            onHold = { addToPlaylist(it) },
                        ) { it.album }
                    }
                }
            }
            UpdatingLine(visible = library.updating)
            NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }

    private fun play(artist: Artist, songs: List<Song>, index: Int) {
        PlaybackHub.playSongs(songs, index, QueueSource(QueueSource.KIND_ARTIST, artist.key))
        openNowPlaying()
    }
}
