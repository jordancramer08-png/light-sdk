package com.jordan.watch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.jordan.watch.logic.StorageMath
import com.jordan.watch.storage.WatchLibrary
import com.jordan.watch.ui.BackTopBar
import com.jordan.watch.ui.ThemedScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * "Remove from phone" confirmation (the Reader's RemoveScreen style). Watch never deletes
 * video files itself (CLAUDE.md 4): REMOVE hides the movie at once and asks the PC script
 * to delete it on the next sync. Hands back true once asked; back changes nothing.
 */
class RemoveScreen(
    sealedActivity: SealedLightActivity,
    private val id: String,
    private val title: String,
) : SimpleLightScreen<Boolean>(sealedActivity) {

    @Composable
    override fun Content() {
        val state by WatchLibrary.state.collectAsState()
        val bytes = state.library.movie(id)?.movie?.bytes
        val size = bytes?.takeIf { it > 0 }?.let { " (" + StorageMath.formatBytes(it) + ")" } ?: ""
        ThemedScreen {
            BackTopBar("Remove", onBack = { goBack() })
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 2f.gridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                LightText(
                    text = "Remove $title$size from the phone?\n\n$EXPLAIN",
                    variant = LightTextVariant.Copy,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LightBottomBar(items = listOf(LightBarButton.Text(text = "REMOVE", onClick = ::remove)))
        }
    }

    private fun remove() {
        WatchLibrary.inBackground { WatchLibrary.repositories.removals.request(id) }
        goBack(true)
    }

    private companion object {
        const val EXPLAIN =
            "It disappears from Watch now. The file is deleted the next time you run Watch-Phone-Sync.cmd.\n\n" +
                "Your place and watched mark are kept, so it resumes if you send it again."
    }
}
