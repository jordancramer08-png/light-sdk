package com.jordan.watch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.jordan.watch.logic.Browse
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

/** Movies and shows Jordan added to his Watchlist, in the order he added them. */
class WatchlistScreen(sealedActivity: SealedLightActivity) : VolumeKeyScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val state by WatchLibrary.state.collectAsState()
        val positions by WatchLibrary.repositories.playback.positions.collectAsState()
        val watched by WatchLibrary.repositories.playback.watched.collectAsState()
        val ids by WatchLibrary.repositories.watchlist.ids.collectAsState()
        val removals by WatchLibrary.repositories.removals.ids.collectAsState()
        ThemedScreen {
            BackTopBar("Watchlist", onBack = { goBack() })
            val tiles = TitleLists.watchlist(Browse.visible(state.library, removals), ids, positions, watched)
            if (tiles.isEmpty()) {
                CenteredMessage(EMPTY, Modifier.weight(1f))
            } else {
                WatchLazyList("watchlist", LIST_ROW_GRID_UNITS, Modifier.weight(1f)) {
                    posterList(tiles, { openTitle(it.id) })
                }
            }
        }
    }

    private companion object {
        const val EMPTY = "Your Watchlist is empty.\n\nOpen a movie or show and choose Add to Watchlist."
    }
}
