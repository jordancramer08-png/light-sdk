package com.jordan.watch

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.jordan.watch.logic.Browse
import com.jordan.watch.logic.BrowseTab
import com.jordan.watch.logic.Filters
import com.jordan.watch.logic.LibraryFilter
import com.jordan.watch.logic.Quality
import com.jordan.watch.logic.SortKey
import com.jordan.watch.logic.SortLabels
import com.jordan.watch.logic.TabView
import com.jordan.watch.logic.WatchState
import com.jordan.watch.storage.WatchLibrary
import com.jordan.watch.ui.ActionRow
import com.jordan.watch.ui.BackTopBar
import com.jordan.watch.ui.ChoiceSection
import com.jordan.watch.ui.ThemedScreen
import com.thelightphone.sdk.SealedLightActivity
import com.jordan.watch.ui.VolumeKeyScreen
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Sort & Filter for one tab, in the Reader's style: Sort by, Order (A–Z / Z–A, or "Newest
 * first" etc.), then each filter with "Any" first. The current choice has a filled circle.
 * Every tap is saved at once for this tab (settings.json) and the screen stays open; back
 * returns to the library. Filter choices come from what's actually in the library.
 */
class SortFilterScreen(sealedActivity: SealedLightActivity, private val tab: BrowseTab) : VolumeKeyScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val prefs by WatchLibrary.repositories.settings.settings.collectAsState()
        val state by WatchLibrary.state.collectAsState()
        val view = prefs.tabView(tab)
        val f = view.filter
        val items = remember(state.library) {
            Browse.items(tab, state.library, emptyMap(), emptyMap())
        }
        ThemedScreen {
            BackTopBar("Sort & Filter", onBack = { goBack() })
            LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
                ChoiceSection("Sort by", SortKey.entries, view.sort, { it.label }) { save(view.copy(sort = it)) }
                ChoiceSection("Order", listOf(true, false), view.ascending, { SortLabels.order(view.sort, it) }) {
                    save(view.copy(ascending = it))
                }
                if (!f.isEmpty) {
                    Spacer(Modifier.height(1f.gridUnitsAsDp()))
                    ActionRow("Clear all filters") { save(view.copy(filter = LibraryFilter())) }
                }
                FilterSection("Show", WatchState.entries, f.watchState, SortLabels::watchState) { filter(view) { f -> f.copy(watchState = it) } }
                FilterSection("Genre", Filters.genres(items), f.genre, { it }) { filter(view) { f -> f.copy(genre = it) } }
                FilterSection("Decade", Filters.decades(items), f.decade, SortLabels::decade) { filter(view) { f -> f.copy(decade = it) } }
                FilterSection("Content rating", Filters.contentRatings(items), f.contentRating, { it }) {
                    filter(view) { f -> f.copy(contentRating = it) }
                }
                if (tab == BrowseTab.MOVIES) {
                    FilterSection("Collection", Filters.collections(items), f.collection, { it }) { filter(view) { f -> f.copy(collection = it) } }
                }
                if (tab != BrowseTab.TV) {
                    FilterSection("Quality", Quality.entries, f.quality, { it.label }) { filter(view) { f -> f.copy(quality = it) } }
                }
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
            }
        }
    }

    /** "Any" then [choices]; hidden when the library has nothing to choose between. */
    @Composable
    private fun <T : Any> FilterSection(heading: String, choices: List<T>, current: T?, label: (T) -> String, onSelect: (T?) -> Unit) {
        if (choices.isEmpty() && current == null) return
        val all: List<T?> = listOf<T?>(null) + choices
        ChoiceSection(heading, all, current, { it?.let(label) ?: "Any" }, onSelect)
    }

    private fun filter(view: TabView, change: (LibraryFilter) -> LibraryFilter) = save(view.copy(filter = change(view.filter)))

    private fun save(view: TabView) {
        WatchLibrary.inBackground {
            WatchLibrary.repositories.settings.update { it.copy(tabViews = it.tabViews + (tab.name to view)) }
        }
    }
}
