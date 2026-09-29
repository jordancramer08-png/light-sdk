package com.thelightphone.listen.music

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.storage.Settings
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.NowPlayingBar
import com.thelightphone.listen.ui.SortField
import com.thelightphone.listen.ui.SortScreen
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.UpdatingLine
import com.thelightphone.sdk.SealedLightActivity

/** Every album with its cover, sorted by album or artist (A–Z or Z–A, remembered). */
class AlbumsScreen(sealedActivity: SealedLightActivity) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val library by MusicLibrary.state.collectAsState()
        val settings by Settings.settings.collectAsState()
        val settingsLoaded by Settings.loaded.collectAsState()
        val sort = settings.albumSort.let { it.copy(field = it.fieldOr(AlbumSortField.ALBUM).name) }
        val sorted = rememberSorted(library.albums, sort) {
            sortAlbums(it, sort.fieldOr(AlbumSortField.ALBUM), sort.descending)
        }
        ThemedScreen {
            ListTopBar(title = "Albums", onBack = { goBack() }, onSort = { openSort(sort) })
            val listArea = Modifier.weight(1f)
            when {
                !library.loaded || !settingsLoaded || sorted == null -> Box(modifier = listArea)
                sorted.items.isNotEmpty() -> Box(modifier = listArea) {
                    MusicLazyList(tag = sorted.tag, rowGridUnits = ALBUM_ROW_GRID_UNITS) {
                        albumRows(sorted.items, onOpen = { openAlbum(it) })
                    }
                }
                library.updating -> CenteredMessage("Reading your music…", modifier = listArea)
                else -> CenteredMessage(NO_MUSIC_MESSAGE, modifier = listArea)
            }
            UpdatingLine(visible = library.updating)
            NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }

    private fun openSort(current: ListSort) {
        navigateTo(
            screenFactory = { SortScreen(it, "Sort albums", FIELDS, current) },
            resultCallback = { chosen -> Settings.change { it.copy(albumSort = chosen) } },
        )
    }

    private companion object {
        val FIELDS = AlbumSortField.entries.map { SortField(it.name, it.label) }
    }
}

/** Every album artist with their first album's cover, A–Z or Z–A by name (remembered). */
class ArtistsScreen(sealedActivity: SealedLightActivity) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val library by MusicLibrary.state.collectAsState()
        val settings by Settings.settings.collectAsState()
        val settingsLoaded by Settings.loaded.collectAsState()
        val sort = ListSort(NAME_FIELD.name, settings.artistSort.descending)
        val sorted = rememberSorted(library.artists, sort) { sortArtists(it, sort.descending) }
        ThemedScreen {
            ListTopBar(title = "Artists", onBack = { goBack() }, onSort = { openSort(sort) })
            val listArea = Modifier.weight(1f)
            when {
                !library.loaded || !settingsLoaded || sorted == null -> Box(modifier = listArea)
                sorted.items.isNotEmpty() -> Box(modifier = listArea) {
                    MusicLazyList(tag = sorted.tag, rowGridUnits = ALBUM_ROW_GRID_UNITS) {
                        artistRows(sorted.items, onOpen = { openArtist(it) })
                    }
                }
                library.updating -> CenteredMessage("Reading your music…", modifier = listArea)
                else -> CenteredMessage(NO_MUSIC_MESSAGE, modifier = listArea)
            }
            UpdatingLine(visible = library.updating)
            NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }

    private fun openSort(current: ListSort) {
        navigateTo(
            screenFactory = { SortScreen(it, "Sort artists", listOf(NAME_FIELD), current) },
            resultCallback = { chosen -> Settings.change { it.copy(artistSort = chosen) } },
        )
    }

    private companion object {
        val NAME_FIELD = SortField("NAME", "Name")
    }
}

const val NO_MUSIC_MESSAGE = "No music on the phone yet.\n\nSend some with Listen-Phone-Sync.cmd on the PC."
