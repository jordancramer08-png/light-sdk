package com.thelightphone.listen.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.OneLine
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.UniformRow
import com.thelightphone.listen.ui.UpdatingLine
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/** Each song row is this tall (grid units), divider included: title, then artist. */
private const val SONG_ROW_GRID_UNITS = 5f

/**
 * Every song, A–Z by title, with its artist underneath. Only the rows on screen are drawn,
 * so 1,400 songs scroll smoothly.
 */
class SongsScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    override fun willShow() {
        super.willShow()
        MusicLibrary.refresh(lightContext.filesDir)
    }

    @Composable
    override fun Content() {
        val library by MusicLibrary.state.collectAsState()
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Songs"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            val listArea = Modifier.weight(1f)
            when {
                !library.loaded -> Box(modifier = listArea)
                library.songs.isNotEmpty() -> Box(modifier = listArea) { SongList(library.songs) }
                library.updating -> CenteredMessage("Reading your music…", modifier = listArea)
                else -> CenteredMessage(NO_MUSIC_MESSAGE, modifier = listArea)
            }
            UpdatingLine(visible = library.updating)
        }
    }

    private companion object {
        const val NO_MUSIC_MESSAGE =
            "No music on the phone yet.\n\nSend some with Listen-Phone-Sync.cmd on the PC."
    }
}

@Composable
private fun SongList(songs: List<Song>) {
    LightLazyScrollView(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        uniformItemHeightGridUnits = SONG_ROW_GRID_UNITS,
    ) {
        itemsIndexed(songs, key = { _, song -> song.path }) { index, song ->
            UniformRow(heightGridUnits = SONG_ROW_GRID_UNITS, showDivider = index != songs.lastIndex) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OneLine(text = song.title, variant = LightTextVariant.Copy)
                    OneLine(text = song.artist, variant = LightTextVariant.Detail, lighten = true)
                }
            }
        }
    }
}
