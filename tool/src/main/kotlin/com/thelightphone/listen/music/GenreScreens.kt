package com.thelightphone.listen.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.playback.PlaybackHub
import com.thelightphone.listen.playback.QueueSource
import com.thelightphone.listen.ui.ActionButton
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.NowPlayingBar
import com.thelightphone.listen.ui.OneLine
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.UniformRow
import com.thelightphone.listen.ui.UpdatingLine
import com.thelightphone.listen.ui.tapOrHold
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlin.random.Random

/** Every genre with one of its covers and its song count, A–Z ("No Genre" last), with the A–Z bar. */
class GenresScreen(sealedActivity: SealedLightActivity) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val library by MusicLibrary.state.collectAsState()
        val genres = library.genres
        ThemedScreen {
            ListTopBar(title = "Genres", onBack = { goBack() })
            val listArea = Modifier.weight(1f)
            when {
                !library.loaded -> Box(modifier = listArea)
                genres.isNotEmpty() -> Box(modifier = listArea) {
                    val letters = remember(genres) { genres.map { if (it.isNoGenre) null else indexLetter(it.name) } }
                    IndexedMusicList(genres, ALBUM_ROW_GRID_UNITS, letters, descending = false) {
                        genreRows(genres, onOpen = { openGenre(it) }, onHold = { showActions(it) })
                    }
                }
                library.updating -> CenteredMessage("Reading your music…", modifier = listArea)
                else -> CenteredMessage(NO_MUSIC_MESSAGE, modifier = listArea)
            }
            UpdatingLine(visible = library.updating)
            NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }
}

/** A genre: Play and Shuffle, its albums with covers, then "All songs". */
class GenreScreen(sealedActivity: SealedLightActivity, private val genreKey: String) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val library by MusicLibrary.state.collectAsState()
        val genre = library.genre(genreKey)
        ThemedScreen {
            ListTopBar(
                title = genre?.name ?: "Genre",
                onBack = { goBack() },
                onAddToPlaylist = genre?.let { { addToPlaylist(it) } },
            )
            val listArea = Modifier.weight(1f)
            when {
                !library.loaded -> Box(modifier = listArea)
                genre == null -> CenteredMessage("No music with this genre is on the phone now.", modifier = listArea)
                else -> Column(modifier = listArea) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp())
                            .padding(bottom = 0.75f.gridUnitsAsDp()),
                        horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
                    ) {
                        ActionButton("Play", LightIcons.PLAY, Modifier.weight(1f)) { play(genre, 0, shuffle = false) }
                        ActionButton("Shuffle", LightIcons.SHUFFLE, Modifier.weight(1f)) {
                            play(genre, Random.nextInt(genre.songs.size), shuffle = true)
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        MusicLazyList(tag = genre.key, rowGridUnits = ALBUM_ROW_GRID_UNITS) {
                            albumRows(genre.albums, onOpen = { openAlbum(it) }, onHold = { showActions(it) }, lastHasDivider = true)
                            item(key = "all-songs") {
                                UniformRow(
                                    heightGridUnits = ALBUM_ROW_GRID_UNITS,
                                    showDivider = false,
                                    modifier = Modifier.tapOrHold(onHold = { showActions(genre) }) { openAllSongs(genre) },
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        OneLine(text = "All songs", variant = LightTextVariant.Copy)
                                        OneLine(text = songCount(genre.songs.size), variant = LightTextVariant.Detail, lighten = true)
                                    }
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

    private fun play(genre: Genre, index: Int, shuffle: Boolean) {
        playGenre(genre, genre.songs, index, shuffle)
        openNowPlaying()
    }

    private fun openAllSongs(genre: Genre) {
        navigateTo(screenFactory = { GenreSongsScreen(it, genre.key) })
    }
}

/** All of a genre's songs, album by album, with the artist under each title. Tapping one plays them all from there. */
class GenreSongsScreen(sealedActivity: SealedLightActivity, private val genreKey: String) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val library by MusicLibrary.state.collectAsState()
        val genre = library.genre(genreKey)
        ThemedScreen {
            ListTopBar(
                title = genre?.name ?: "Genre",
                onBack = { goBack() },
                onAddToPlaylist = genre?.let { { addToPlaylist(it) } },
            )
            val listArea = Modifier.weight(1f)
            when {
                !library.loaded -> Box(modifier = listArea)
                genre == null -> CenteredMessage("No music with this genre is on the phone now.", modifier = listArea)
                else -> Box(modifier = listArea) {
                    MusicLazyList(tag = genre.key, rowGridUnits = SONG_ROW_GRID_UNITS) {
                        songRows(
                            genre.songs,
                            library,
                            onPlay = { songs, index ->
                                playGenre(genre, songs, index, shuffle = null)
                                openNowPlaying()
                            },
                            onHold = { showActions(it) },
                        )
                    }
                }
            }
            UpdatingLine(visible = library.updating)
            NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }
}

private fun playGenre(genre: Genre, songs: List<Song>, index: Int, shuffle: Boolean?) {
    PlaybackHub.playSongs(songs, index, QueueSource(QueueSource.KIND_GENRE, genre.key), shuffle = shuffle)
}
