package com.thelightphone.listen.music

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.playback.PlaybackHub
import com.thelightphone.listen.playback.QueueSource
import com.thelightphone.listen.storage.Settings
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.NowPlayingBar
import com.thelightphone.listen.ui.SortField
import com.thelightphone.listen.ui.SortScreen
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.UpdatingLine
import com.thelightphone.sdk.SealedLightActivity

/**
 * Every song, sorted by title, artist or album (A–Z or Z–A, remembered), each with its
 * album's small cover. Only the rows on screen are drawn, so 1,400 songs scroll smoothly.
 * Tapping a song plays the list, in the order shown, from that song.
 */
class SongsScreen(sealedActivity: SealedLightActivity) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val library by MusicLibrary.state.collectAsState()
        val settings by Settings.settings.collectAsState()
        val settingsLoaded by Settings.loaded.collectAsState()
        val sort = currentSort(settings.songSort)
        val sorted = rememberSorted(library.songs, sort) {
            sortSongs(it, sort.fieldOr(SongSortField.TITLE), sort.descending)
        }
        ThemedScreen {
            ListTopBar(title = "Songs", onBack = { goBack() }, onSort = { openSort(sort) })
            val listArea = Modifier.weight(1f)
            when {
                !library.loaded || !settingsLoaded || sorted == null -> Box(modifier = listArea)
                sorted.items.isNotEmpty() -> Box(modifier = listArea) {
                    MusicLazyList(tag = sorted.tag, rowGridUnits = SONG_ROW_GRID_UNITS) {
                        songRows(sorted.items, library, onPlay = ::play, onHold = { addToPlaylist(it) })
                    }
                }
                library.updating -> CenteredMessage("Reading your music…", modifier = listArea)
                else -> CenteredMessage(NO_MUSIC_MESSAGE, modifier = listArea)
            }
            UpdatingLine(visible = library.updating)
            NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }

    private fun play(songs: List<Song>, index: Int) {
        PlaybackHub.playSongs(songs, index, QueueSource())
        openNowPlaying()
    }

    private fun openSort(current: ListSort) {
        navigateTo(
            screenFactory = { SortScreen(it, "Sort songs", FIELDS, current) },
            resultCallback = { chosen -> Settings.change { it.copy(songSort = chosen) } },
        )
    }

    private companion object {
        val FIELDS = SongSortField.entries.map { SortField(it.name, it.label) }

        /** The saved sort with its field spelled out (Title when none was saved). */
        fun currentSort(saved: ListSort) = saved.copy(field = saved.fieldOr(SongSortField.TITLE).name)
    }
}
