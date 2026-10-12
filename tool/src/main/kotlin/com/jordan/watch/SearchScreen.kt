package com.jordan.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.jordan.watch.logic.Browse
import com.jordan.watch.logic.BrowseTab
import com.jordan.watch.logic.TabView
import com.jordan.watch.logic.Tile
import com.jordan.watch.storage.WatchLibrary
import com.jordan.watch.ui.BackTopBar
import com.jordan.watch.ui.CenteredMessage
import com.jordan.watch.ui.LIST_ROW_GRID_UNITS
import com.jordan.watch.ui.ThemedScreen
import com.jordan.watch.ui.WatchLazyList
import com.jordan.watch.ui.headingRow
import com.jordan.watch.ui.posterList
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Search movies and shows by title, cast or director: every typed word must match, in any
 * order, ignoring capitals and accents. The words are typed on the SDK's full-screen
 * keyboard; SEARCH shows the results, tapping the box types again.
 */
class SearchScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    // Kept on the screen so the words are still there when you come back from a title.
    private val query = TextFieldState()
    private var keyboardShown by mutableStateOf(true)
    private val keyboardKey = Any()

    @Composable
    override fun Content() {
        val state by WatchLibrary.state.collectAsState()
        val positions by WatchLibrary.repositories.playback.positions.collectAsState()
        val watched by WatchLibrary.repositories.playback.watched.collectAsState()
        val removals by WatchLibrary.repositories.removals.ids.collectAsState()
        val typed = query.text.toString()
        val results by produceState(emptyList<Tile>() to emptyList<Tile>(), state.library, typed, removals) {
            value = withContext(Dispatchers.Default) {
                val library = Browse.visible(state.library, removals)
                Browse.tiles(BrowseTab.MOVIES, library, positions, watched, TabView(), typed) to
                    Browse.tiles(BrowseTab.TV, library, positions, watched, TabView(), typed)
            }
        }
        if (keyboardShown) {
            ThemedScreen {
                LightTextInputEditor(
                    title = "Search",
                    state = query,
                    onSubmit = { keyboardShown = false },
                    onBack = { if (query.text.isEmpty()) goBack() else keyboardShown = false },
                    keyboardOptionsFlow = rememberKeyboardOptions(),
                    submitLabel = "SEARCH",
                    singleLine = true,
                    editorKey = keyboardKey,
                )
            }
            return
        }
        ThemedScreen {
            BackTopBar("Search", onBack = { goBack() })
            SearchBox(typed)
            val area = Modifier.weight(1f)
            val (movies, shows) = results
            when {
                typed.isBlank() -> Box(area)
                movies.isEmpty() && shows.isEmpty() -> CenteredMessage("Nothing found.", area)
                else -> WatchLazyList("search", LIST_ROW_GRID_UNITS, area) {
                    if (movies.isNotEmpty()) {
                        headingRow("Movies")
                        posterList(movies, ::open, keyPrefix = "m:")
                    }
                    if (shows.isNotEmpty()) {
                        headingRow("TV")
                        posterList(shows, ::open, keyPrefix = "s:")
                    }
                }
            }
        }
    }

    private fun open(tile: Tile) {
        keyboardShown = false
        openTitle(tile.id)
    }

    @Composable
    private fun SearchBox(typed: String) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()).padding(bottom = 0.75f.gridUnitsAsDp())
                .lightClickable(onClickLabel = "Type", onClick = { keyboardShown = true }),
        ) {
            LightText(
                text = typed.ifEmpty { "Title, actor or director" },
                variant = LightTextVariant.Subheading,
                lighten = typed.isEmpty(),
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
            Spacer(Modifier.height(0.4f.gridUnitsAsDp()))
            Spacer(Modifier.fillMaxWidth().height(3f.designVerticalPxToDp()).background(LightThemeTokens.colors.content))
        }
    }
}
