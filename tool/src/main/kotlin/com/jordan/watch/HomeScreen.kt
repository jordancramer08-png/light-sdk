package com.jordan.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jordan.watch.storage.LibraryState
import com.jordan.watch.storage.PosterCache
import com.jordan.watch.storage.StorageAccess
import com.jordan.watch.storage.WatchLibrary
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Watch's first screen. A placeholder until the library arrives (Session 4). Without
 * All files access it shows how to grant it instead, and checks again each time Watch
 * comes back to the front (same as Listen).
 */
@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    /** Null until checked, then whether Watch can read /sdcard/Watch. */
    private val hasAccess = MutableStateFlow<Boolean?>(null)

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
        val colors by LightThemeController.colors.collectAsState()
        LightTheme(colors = colors) {
            Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(center = LightTopBarCenter.Text("Watch"))
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 2f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(text = messageFor(access, library), variant = LightTextVariant.Paragraph)
                }
            }
        }
    }

    private fun messageFor(access: Boolean?, state: LibraryState): String = when {
        access == null -> ""
        access == false -> NO_ACCESS_MESSAGE
        state.updating -> "Updating library..."
        !state.loaded -> ""
        else -> "${state.library.movies.size} movies, ${state.library.shows.size} shows.\n\nThe library screens arrive next."
    }

    private companion object {
        const val NO_ACCESS_MESSAGE =
            "Watch can't see its files yet.\n\n" +
                "Run Watch-Phone-Sync.cmd on the PC and choose the option that lets Watch read files.\n\n" +
                "Then close Watch and open it again."
    }
}
