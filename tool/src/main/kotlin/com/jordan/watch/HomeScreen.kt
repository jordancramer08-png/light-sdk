package com.jordan.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.jordan.watch.logic.Browse
import com.jordan.watch.logic.BrowseTab
import com.jordan.watch.logic.Library
import com.jordan.watch.logic.SortLabels
import com.jordan.watch.logic.Position
import com.jordan.watch.logic.Shelves
import com.jordan.watch.logic.Tile
import com.jordan.watch.logic.WatchSettings
import com.jordan.watch.storage.LibraryState
import com.jordan.watch.storage.PosterCache
import com.jordan.watch.storage.StorageAccess
import com.jordan.watch.storage.WatchLibrary
import com.jordan.watch.ui.ButtonStrip
import com.jordan.watch.ui.CenteredMessage
import com.jordan.watch.ui.LIST_ROW_GRID_UNITS
import com.jordan.watch.ui.LocalAccent
import com.jordan.watch.ui.ThemedScreen
import com.jordan.watch.ui.WatchLazyList
import com.jordan.watch.ui.gridRowGridUnits
import com.jordan.watch.ui.headingRow
import com.jordan.watch.ui.posterGrid
import com.jordan.watch.ui.posterList
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** The four places at the bottom of the library. */
enum class Tab(val label: String) { HOME("Home"), MOVIES("Movies"), TV("TV"), COLLECTIONS("Collections") }

/**
 * Watch's library: Home (Continue Watching, then Recently Added) and the Movies, TV and
 * Collections tabs, as a poster grid (2 or 3 columns) or a list with small posters. The
 * top bar switches grid/list (left) and opens Settings (right). Under it: Sort & Filter
 * (Watchlist on Home), Search and Surprise me.
 * Without All files access it shows how to grant it, and checks again on every return.
 */
@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    /** Null until checked, then whether Watch can read /sdcard/Watch. */
    private val hasAccess = MutableStateFlow<Boolean?>(null)
    private val tab = MutableStateFlow(Tab.HOME)

    private val settings get() = WatchLibrary.repositories.settings

    override fun willShow() {
        super.willShow()
        hasAccess.value = StorageAccess.hasAllFilesAccess()
        PosterCache.setFilesDir(lightContext.filesDir)
        WatchLibrary.refresh(lightContext.filesDir)
    }

    @Composable
    override fun Content() {
        val access by hasAccess.collectAsState()
        val library by WatchLibrary.state.collectAsState()
        val current by tab.collectAsState()
        val prefs by settings.settings.collectAsState()
        val grid = prefs.libraryView != "LIST"
        val columns = if (prefs.gridColumns == 2) 2 else 3
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = if (grid) LightIcons.LIST else LightIcons.LARGE_LIST,
                    onClick = ::toggleView,
                    contentDescription = if (grid) "Show as list" else "Show as posters",
                ),
                center = LightTopBarCenter.Text(if (library.updating) "Updating library..." else "Watch"),
                rightButton = LightBarButton.LightIcon(
                    icon = LightIcons.SETTINGS,
                    onClick = { navigateTo({ SettingsScreen(it) }) },
                    contentDescription = "Settings",
                ),
            )
            if (library.loaded && !library.library.isEmpty) ActionStrip(current, prefs)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    access == false && !library.fake -> CenteredMessage(NO_ACCESS_MESSAGE, Modifier.matchParentSize())
                    !library.loaded -> Unit
                    library.library.isEmpty -> CenteredMessage(EMPTY_MESSAGE, Modifier.matchParentSize())
                    else -> TabContent(current, library, grid, columns)
                }
            }
            TabBar(current) { tab.value = it }
        }
    }

    @Composable
    private fun TabContent(current: Tab, state: LibraryState, grid: Boolean, columns: Int) {
        val positions by WatchLibrary.repositories.playback.positions.collectAsState()
        val watched by WatchLibrary.repositories.playback.watched.collectAsState()
        val removals by WatchLibrary.repositories.removals.ids.collectAsState()
        val prefs by settings.settings.collectAsState()
        val library = Browse.visible(state.library, removals)
        val tiles = rememberTiles(current, library, positions, watched, prefs) ?: return
        val tag = listOf(current, grid, columns, browseTab(current)?.let { prefs.tabView(it) })
        when {
            current == Tab.HOME -> WatchLazyList(tag, LIST_ROW_GRID_UNITS) {
                if (tiles.first.isNotEmpty()) {
                    headingRow("Continue Watching")
                    posterList(tiles.first, ::open, keyPrefix = "continue:")
                }
                headingRow("Recently Added")
                posterList(tiles.second, ::open, keyPrefix = "recent:")
            }
            tiles.second.isEmpty() && browseTab(current)?.let { prefs.tabView(it).filter.isEmpty } == false ->
                CenteredMessage("Nothing matches these filters.\n\nChange them in Sort & Filter.")
            tiles.second.isEmpty() -> CenteredMessage(emptyTabMessage(current))
            grid -> WatchLazyList(tag, gridRowGridUnits(columns)) { posterGrid(tiles.second, columns, ::open) }
            else -> WatchLazyList(tag, LIST_ROW_GRID_UNITS) { posterList(tiles.second, ::open) }
        }
    }

    /**
     * The tab's tiles, worked out off the main thread (300+ titles). Home gives
     * (Continue Watching, Recently Added); the other tabs give (nothing, everything).
     */
    @Composable
    private fun rememberTiles(
        current: Tab,
        library: Library,
        positions: Map<String, Position>,
        watched: Map<String, Long>,
        prefs: WatchSettings,
    ): Pair<List<Tile>, List<Tile>>? {
        val tab = browseTab(current)
        val view = tab?.let { prefs.tabView(it) }
        return produceState<Pair<List<Tile>, List<Tile>>?>(null, current, library, positions, watched, view) {
            value = withContext(Dispatchers.Default) {
                if (tab == null || view == null) {
                    Shelves.continueWatching(library, positions, watched) to Shelves.recentlyAdded(library, watched)
                } else {
                    emptyList<Tile>() to Browse.tiles(tab, library, positions, watched, view)
                }
            }
        }.value
    }

    /** Under the top bar: Watchlist (Home) or Sort & Filter (tabs), then Search and Surprise me. */
    @Composable
    private fun ActionStrip(current: Tab, prefs: WatchSettings) {
        val tab = browseTab(current)
        val first: Pair<String, () -> Unit> = if (tab == null) {
            "Watchlist" to { navigateTo({ WatchlistScreen(it) }) }
        } else {
            val filters = SortLabels.filterCount(prefs.tabView(tab).filter)
            (if (filters > 0) "Sort & Filter ($filters)" else "Sort & Filter") to { navigateTo({ SortFilterScreen(it, tab) }) }
        }
        ButtonStrip(
            listOf(
                first,
                "Search" to { navigateTo({ SearchScreen(it) }) },
                "Surprise me" to { navigateTo({ SurpriseScreen(it) }) },
            ),
        )
    }

    private fun browseTab(tab: Tab): BrowseTab? = when (tab) {
        Tab.HOME -> null
        Tab.MOVIES -> BrowseTab.MOVIES
        Tab.TV -> BrowseTab.TV
        Tab.COLLECTIONS -> BrowseTab.COLLECTIONS
    }

    /** The tab bar. The chosen tab is underlined and in full color; the others are lighter. */
    @Composable
    private fun TabBar(current: Tab, onPick: (Tab) -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().height(3f.gridUnitsAsDp()).padding(horizontal = 1f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (t in Tab.entries) {
                val chosen = t == current
                Column(
                    modifier = Modifier.weight(1f).height(3f.gridUnitsAsDp()).lightClickable { onPick(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        LightText(text = t.label, variant = LightTextVariant.Detail, lighten = !chosen, maxLines = 1)
                    }
                    Box(
                        Modifier.fillMaxWidth(0.7f).height(6f.designVerticalPxToDp())
                            .background(if (chosen) LocalAccent.current else Color.Transparent),
                    )
                }
            }
        }
    }

    private fun toggleView() = changeSettings { it.copy(libraryView = if (it.libraryView == "LIST") "GRID" else "LIST") }

    /** Saves a settings change off the main thread (it writes settings.json). */
    private fun changeSettings(change: (WatchSettings) -> WatchSettings) = WatchLibrary.inBackground { settings.update(change) }

    private fun open(tile: Tile) = openTitle(tile.id)

    private fun emptyTabMessage(tab: Tab): String = when (tab) {
        Tab.TV -> "No TV shows yet.\n\nSend some with Watch-Phone-Sync.cmd."
        Tab.COLLECTIONS -> "No collections yet.\n\nMovies in a folder on the PC become a collection."
        else -> EMPTY_MESSAGE
    }

    private companion object {
        const val NO_ACCESS_MESSAGE =
            "Watch can't see its files yet.\n\n" +
                "Run Watch-Phone-Sync.cmd on the PC and choose the option that lets Watch read files.\n\n" +
                "Then close Watch and open it again."
        const val EMPTY_MESSAGE = "Nothing here yet.\n\nSend movies and shows with Watch-Phone-Sync.cmd."
    }
}
