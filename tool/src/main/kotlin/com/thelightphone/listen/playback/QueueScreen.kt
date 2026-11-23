package com.thelightphone.listen.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.artwork.ArtImage
import com.thelightphone.listen.artwork.ArtSize
import com.thelightphone.listen.artwork.artLetter
import com.thelightphone.listen.artwork.songArtSource
import com.thelightphone.listen.music.ArtAndText
import com.thelightphone.listen.music.ListTopBar
import com.thelightphone.listen.music.MusicLazyList
import com.thelightphone.listen.music.MusicLibrary
import com.thelightphone.listen.music.MusicLibraryState
import com.thelightphone.listen.music.SONG_ROW_GRID_UNITS
import com.thelightphone.listen.music.Song
import com.thelightphone.listen.music.songCount
import com.thelightphone.listen.playlists.PlaylistEntry
import com.thelightphone.listen.playlists.PlaylistNameScreen
import com.thelightphone.listen.playlists.Playlists
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.ConfirmScreen
import com.thelightphone.listen.ui.LocalListenAccent
import com.thelightphone.listen.ui.OneLine
import com.thelightphone.listen.ui.RowIconButton
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.UniformRow
import com.thelightphone.listen.ui.showNotice
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow

private const val ART_GRID_UNITS = 3.8f

/**
 * The music queue in the order it plays (the shuffled order while shuffle is on): the
 * current song highlighted at the top, then "Up next". Played songs hide behind "Show
 * played". Tapping a song plays it; up, down and the bin edit the queue. At the bottom:
 * "Clear up next" (asks first) and "Save queue as playlist…".
 */
class QueueScreen(sealedActivity: SealedLightActivity) : ListenScreen(sealedActivity) {

    private val showPlayed = MutableStateFlow(false)

    @Composable
    override fun Content() {
        val queue by PlaybackHub.queue.collectAsState()
        val index by PlaybackHub.index.collectAsState()
        val book by PlaybackHub.book.collectAsState()
        val library by MusicLibrary.state.collectAsState()
        val played by showPlayed.collectAsState()
        val songs = queue?.songs.orEmpty()
        ThemedScreen {
            ListTopBar(title = "Queue", onBack = { goBack() })
            val area = Modifier.weight(1f)
            if (book != null || index !in songs.indices) {
                CenteredMessage("Nothing is queued.\n\nPick a song in Music.", modifier = area)
            } else {
                Box(modifier = area) {
                    MusicLazyList(tag = "queue", rowGridUnits = SONG_ROW_GRID_UNITS) {
                        queueRows(songs, index, library, played)
                    }
                }
            }
        }
    }

    private fun LazyListScope.queueRows(songs: List<Song>, current: Int, library: MusicLibraryState, played: Boolean) {
        if (current > 0) {
            item(key = "played-toggle") {
                TextRow(
                    text = if (played) "Hide played" else "Show played",
                    detail = songCount(current),
                ) { showPlayed.value = !played }
            }
            if (played) {
                val earlier = songs.take(current)
                itemsIndexed(earlier, key = { i, s -> "played:$i:${s.path}" }) { i, song ->
                    SongRow(song, library, onPlay = { PlaybackHub.jumpTo(i, song.path) }) {
                        RowIconButton(LightIcons.TRASH, "Remove") { PlaybackHub.removeFromQueue(i, song.path) }
                    }
                }
            }
        }
        item(key = "current:${songs[current].path}") {
            val accent = LocalListenAccent.current
            Box(modifier = Modifier.fillMaxSize().background(accent.copy(alpha = CURRENT_TINT))) {
                SongRow(songs[current], library, detailPrefix = "Now playing · ", onPlay = { }) {}
            }
        }
        val upNext = songs.size - current - 1
        item(key = "up-next") {
            TextRow(text = "Up next", detail = if (upNext == 0) "Nothing yet" else songCount(upNext), onClick = null)
        }
        val later = songs.drop(current + 1)
        itemsIndexed(later, key = { i, s -> "next:$i:${s.path}" }) { i, song ->
            val at = current + 1 + i
            SongRow(song, library, onPlay = { PlaybackHub.jumpTo(at, song.path) }) {
                RowIconButton(LightIcons.UP, "Move up", enabled = i > 0) { PlaybackHub.moveInQueue(at, up = true, song.path) }
                RowIconButton(LightIcons.DOWN, "Move down", enabled = i < later.lastIndex) {
                    PlaybackHub.moveInQueue(at, up = false, song.path)
                }
                RowIconButton(LightIcons.TRASH, "Remove") { PlaybackHub.removeFromQueue(at, song.path) }
            }
        }
        if (upNext > 0) {
            item(key = "clear") { TextRow(text = "Clear up next", detail = null, onClick = ::confirmClear) }
        }
        item(key = "save") { TextRow(text = "Save queue as playlist…", detail = null, onClick = ::saveAsPlaylist) }
    }

    private fun confirmClear() {
        navigateTo(
            screenFactory = { ConfirmScreen(it, "Clear up next", "Take every song after this one out of the queue?", "CLEAR") },
            resultCallback = { confirmed -> if (confirmed) PlaybackHub.clearUpNext() },
        )
    }

    private fun saveAsPlaylist() {
        val songs = PlaybackHub.queue.value?.songs.orEmpty()
        if (songs.isEmpty()) return
        navigateTo(
            screenFactory = { PlaylistNameScreen(it, title = "Save queue as playlist") },
            resultCallback = { name ->
                Playlists.createWith(name, songs.map { PlaylistEntry.song(it.path) })
                showNotice("Saved playlist “$name”")
            },
        )
    }

    private companion object {
        const val CURRENT_TINT = 0.18f
    }
}

/** Small cover, title and artist (tap to play), then [buttons] at the right. */
@Composable
private fun SongRow(
    song: Song,
    library: MusicLibraryState,
    detailPrefix: String = "",
    onPlay: () -> Unit,
    buttons: @Composable () -> Unit,
) {
    UniformRow(heightGridUnits = SONG_ROW_GRID_UNITS, showDivider = true) {
        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            ArtAndText(
                art = { size -> ArtImage(songArtSource(song, library.albumOf(song)), ArtSize.THUMB, artLetter(song.album), size) },
                artGridUnits = ART_GRID_UNITS,
                modifier = Modifier.weight(1f).lightClickable(onClick = onPlay),
            ) {
                OneLine(text = song.title, variant = LightTextVariant.Copy)
                OneLine(text = detailPrefix + song.artist, variant = LightTextVariant.Detail, lighten = true)
            }
            buttons()
        }
    }
}

/** A plain row of the queue list: "Up next", "Show played", "Clear up next". */
@Composable
private fun TextRow(text: String, detail: String?, onClick: (() -> Unit)?) {
    UniformRow(
        heightGridUnits = SONG_ROW_GRID_UNITS,
        showDivider = true,
        modifier = if (onClick != null) Modifier.lightClickable(onClick = onClick) else Modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(end = 0.5f.gridUnitsAsDp())) {
            OneLine(text = text, variant = if (onClick == null) LightTextVariant.Detail else LightTextVariant.Copy, lighten = onClick == null)
            if (detail != null) OneLine(text = detail, variant = LightTextVariant.Detail, lighten = true)
        }
    }
}
