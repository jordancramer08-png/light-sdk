package com.thelightphone.listen.playlists

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.music.ListTopBar
import com.thelightphone.listen.music.MusicLazyList
import com.thelightphone.listen.music.MusicLibrary
import com.thelightphone.listen.music.SONG_ROW_GRID_UNITS
import com.thelightphone.listen.music.Song
import com.thelightphone.listen.music.songRows
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.NowPlayingBar
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.UpdatingLine
import com.thelightphone.sdk.SealedLightActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An entry's songs, and the whole playlist's (to play from one of them). */
private data class EntrySongs(val match: EntryMatch, val playlistSongs: List<Song>)

/**
 * The songs an album or artist entry of a playlist stands for right now, in the order they
 * play (with each song's album under its title). Tapping one plays the playlist from there.
 */
class PlaylistEntryScreen(
    sealedActivity: SealedLightActivity,
    private val playlistId: String,
    private val entry: PlaylistEntry,
) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val playlists by Playlists.playlists.collectAsState()
        val library by MusicLibrary.state.collectAsState()
        val playlist = playlists.firstOrNull { it.id == playlistId }
        val shown by produceState<EntrySongs?>(null, playlist, library) {
            value = withContext(Dispatchers.Default) {
                EntrySongs(matchEntry(entry, library), playlist?.let { playlistSongs(it, library) }.orEmpty())
            }
        }
        ThemedScreen {
            val current = shown
            ListTopBar(title = current?.let { entryTitle(it.match) } ?: "", onBack = { goBack() })
            val listArea = Modifier.weight(1f)
            when {
                !library.loaded || current == null -> Box(modifier = listArea)
                !current.match.onPhone -> CenteredMessage(NOT_HERE, modifier = listArea)
                else -> Box(modifier = listArea) {
                    MusicLazyList(tag = entry.matchKey, rowGridUnits = SONG_ROW_GRID_UNITS) {
                        songRows(
                            current.match.songs,
                            library,
                            onPlay = { songs, index -> play(playlist, current, songs[index]) },
                            onHold = { addToPlaylist(it) },
                            detail = if (entry.kind == PlaylistEntry.KIND_ALBUM) {
                                { it.artist }
                            } else {
                                { it.album }
                            },
                        )
                    }
                }
            }
            UpdatingLine(visible = library.updating)
            NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }

    /** Plays the playlist from [song] (its first place in the playlist). */
    private fun play(playlist: Playlist?, shown: EntrySongs, song: Song) {
        val index = shown.playlistSongs.indexOfFirst { it.path == song.path }
        if (playlist == null || index < 0) return
        playPlaylist(playlist, shown.playlistSongs, index)
        openNowPlaying()
    }

    private companion object {
        const val NOT_HERE = "This isn't on the phone right now.\n\n" +
            "It stays in the playlist and plays again when the music is sent back."
    }
}
