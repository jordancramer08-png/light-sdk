package com.jordan.watch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jordan.watch.logic.Browse
import com.jordan.watch.logic.BrowseTab
import com.jordan.watch.logic.Filters
import com.jordan.watch.logic.LibraryItem
import com.jordan.watch.logic.Surprise
import com.jordan.watch.storage.WatchLibrary
import com.jordan.watch.ui.BackTopBar
import com.jordan.watch.ui.CenteredMessage
import com.jordan.watch.ui.HairlineDivider
import com.jordan.watch.ui.ListRow
import com.jordan.watch.ui.ListStepperRow
import com.jordan.watch.ui.ThemedScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * "Surprise me": picks a random movie he hasn't started, optionally of one genre and no
 * longer than a limit. Tap the pick to open it; ANOTHER picks again (never the same one twice
 * in a row unless it's the only one).
 */
class SurpriseScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    private var genre by mutableStateOf<String?>(null)
    private var maxRuntime by mutableStateOf<Int?>(null)
    private var pickedId by mutableStateOf<String?>(null)

    /** False until the first pick, so the screen opens with one already chosen. */
    private var picked by mutableStateOf(false)

    @Composable
    override fun Content() {
        val state by WatchLibrary.state.collectAsState()
        val positions by WatchLibrary.repositories.playback.positions.collectAsState()
        val watched by WatchLibrary.repositories.playback.watched.collectAsState()
        val removals by WatchLibrary.repositories.removals.ids.collectAsState()
        val library = Browse.visible(state.library, removals)
        val items = Browse.items(BrowseTab.MOVIES, library, positions, watched)
        if (!picked) pick(items)

        ThemedScreen {
            BackTopBar("Surprise me", onBack = { goBack() })
            Column(Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
                ListStepperRow("Genre", listOf<String?>(null) + Filters.genres(items), genre, { it ?: "Any" }) {
                    genre = it
                    pick(items)
                }
                ListStepperRow("Length", Surprise.RUNTIME_LIMITS, maxRuntime, Surprise::limitLabel) {
                    maxRuntime = it
                    pick(items)
                }
                HairlineDivider()
            }
            val tile = pickedId?.let { library.movie(it) }?.let { Browse.movieTile(it, positions, watched) }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()), contentAlignment = Alignment.Center) {
                if (tile == null) {
                    CenteredMessage("No unwatched movie fits.\n\nTry another genre or length.")
                } else {
                    ListRow(tile) { openTitle(it.id) }
                }
            }
            LightBottomBar(items = listOf(LightBarButton.Text(text = "ANOTHER", onClick = { pick(items) })))
        }
    }

    private fun pick(items: List<LibraryItem>) {
        picked = true
        pickedId = Surprise.pick(items, genre, maxRuntime, avoidId = pickedId)?.id
    }
}
