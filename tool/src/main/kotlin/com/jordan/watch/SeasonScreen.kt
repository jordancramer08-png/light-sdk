package com.jordan.watch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.jordan.watch.logic.TitleLists
import com.jordan.watch.storage.WatchLibrary
import com.jordan.watch.ui.BackTopBar
import com.jordan.watch.ui.CenteredMessage
import com.jordan.watch.ui.LIST_ROW_GRID_UNITS
import com.jordan.watch.ui.ThemedScreen
import com.jordan.watch.ui.WatchLazyList
import com.jordan.watch.ui.posterList
import com.thelightphone.sdk.SealedLightActivity
import com.jordan.watch.ui.VolumeKeyScreen

/** One season's episodes in order, each with runtime, "· Watched" and a progress bar when started. */
class SeasonScreen(
    sealedActivity: SealedLightActivity,
    private val showId: String,
    private val season: Int,
) : VolumeKeyScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val state by WatchLibrary.state.collectAsState()
        val positions by WatchLibrary.repositories.playback.positions.collectAsState()
        val watched by WatchLibrary.repositories.playback.watched.collectAsState()
        val show = state.library.show(showId)
        ThemedScreen {
            BackTopBar(listOfNotNull(show?.show?.title, TitleLists.seasonName(season)).joinToString(" · "), onBack = { goBack() })
            val tiles = show?.let { TitleLists.episodes(state.library, it, season, positions, watched) }.orEmpty()
            if (tiles.isEmpty()) {
                CenteredMessage("No episodes here.", Modifier.weight(1f))
            } else {
                WatchLazyList(showId to season, LIST_ROW_GRID_UNITS, Modifier.weight(1f)) {
                    posterList(tiles, { openTitle(it.id) })
                }
            }
        }
    }
}
