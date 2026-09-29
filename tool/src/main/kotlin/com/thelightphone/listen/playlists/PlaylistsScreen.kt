package com.thelightphone.listen.playlists

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.music.ListTopBar
import com.thelightphone.listen.music.MusicLibrary
import com.thelightphone.listen.music.sortKey
import com.thelightphone.listen.music.songCount
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.HairlineDivider
import com.thelightphone.listen.ui.NowPlayingBar
import com.thelightphone.listen.ui.OneLine
import com.thelightphone.listen.ui.RowIconButton
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.UpdatingLine
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A playlist as the Playlists list shows it: with how many of its songs are on the phone now. */
private data class PlaylistSummary(val playlist: Playlist, val songsOnPhone: Int)

/**
 * Every playlist, A–Z by name (like the Reader's Lists screen). + makes a new one; each has
 * a pencil (rename) and a bin (delete, after asking). Tapping one opens it.
 */
class PlaylistsScreen(sealedActivity: SealedLightActivity) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val playlists by Playlists.playlists.collectAsState()
        val loaded by Playlists.loaded.collectAsState()
        val library by MusicLibrary.state.collectAsState()
        val summaries by produceState<List<PlaylistSummary>?>(null, playlists, library) {
            value = withContext(Dispatchers.Default) {
                playlists
                    .sortedWith(compareBy({ sortKey(it.name) }, { it.id }))
                    .map { PlaylistSummary(it, playlistSongs(it, library).size) }
            }
        }
        ThemedScreen {
            ListTopBar(
                title = "Playlists",
                onBack = { goBack() },
                rightButton = LightBarButton.LightIcon(
                    icon = LightIcons.ADD,
                    onClick = ::openNewPlaylist,
                    contentDescription = "New playlist",
                ),
            )
            val listArea = Modifier.weight(1f)
            val shown = summaries
            when {
                !loaded || shown == null -> Box(modifier = listArea)
                shown.isEmpty() -> CenteredMessage(NO_PLAYLISTS, modifier = listArea)
                else -> Box(modifier = listArea) {
                    LightScrollView(modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
                        shown.forEachIndexed { i, summary ->
                            PlaylistRow(
                                name = summary.playlist.name,
                                detail = if (library.loaded) songCount(summary.songsOnPhone) else "",
                                onOpen = { openPlaylist(summary.playlist.id) },
                                onRename = { openRename(summary.playlist) },
                                onDelete = { openDelete(summary.playlist) },
                            )
                            if (i != shown.lastIndex) HairlineDivider()
                        }
                    }
                }
            }
            UpdatingLine(visible = library.updating)
            NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }

    private fun openPlaylist(id: String) {
        navigateTo(screenFactory = { PlaylistScreen(it, id) })
    }

    /** Names a new playlist, then opens it. */
    private fun openNewPlaylist() {
        navigateTo(
            screenFactory = { PlaylistNameScreen(it, title = "New playlist") },
            resultCallback = { name -> openPlaylist(Playlists.create(name)) },
        )
    }

    private fun openRename(playlist: Playlist) {
        navigateTo(
            screenFactory = { PlaylistNameScreen(it, title = "Rename playlist", initialName = playlist.name) },
            resultCallback = { name -> Playlists.rename(playlist.id, name) },
        )
    }

    private fun openDelete(playlist: Playlist) {
        navigateTo(
            screenFactory = { DeletePlaylistScreen(it, playlist.name) },
            resultCallback = { confirmed -> if (confirmed) Playlists.delete(playlist.id) },
        )
    }

    private companion object {
        const val NO_PLAYLISTS = "No playlists yet.\n\nTap + to make one."
    }
}

/** The name and song count (tap to open), then rename and delete buttons. */
@Composable
private fun PlaylistRow(name: String, detail: String, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier
                .weight(1f)
                .lightClickable(onClick = onOpen)
                .padding(vertical = 0.75f.gridUnitsAsDp()),
        ) {
            OneLine(text = name, variant = LightTextVariant.Copy)
            OneLine(text = detail, variant = LightTextVariant.Detail, lighten = true)
        }
        RowIconButton(icon = LightIcons.PENCIL, label = "Rename", onClick = onRename)
        RowIconButton(icon = LightIcons.TRASH, label = "Delete", onClick = onDelete)
    }
}
