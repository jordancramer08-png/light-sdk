package com.jordan.watch

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.jordan.watch.logic.WatchSettings
import com.jordan.watch.storage.WatchLibrary
import com.jordan.watch.ui.BackTopBar
import com.jordan.watch.ui.ChoiceSection
import com.jordan.watch.ui.SectionHeading
import com.jordan.watch.ui.ThemedScreen
import com.jordan.watch.ui.WatchTheme
import com.thelightphone.sdk.SealedLightActivity
import com.jordan.watch.ui.VolumeKeyScreen
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Settings (saved in .state/settings.json at each tap): theme, poster columns, library view,
 * skip step, start orientation, subtitle size, then the TMDB credit TMDB's terms require.
 */
class SettingsScreen(sealedActivity: SealedLightActivity) : VolumeKeyScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val s by WatchLibrary.repositories.settings.settings.collectAsState()
        ThemedScreen {
            BackTopBar("Settings", onBack = { goBack() })
            LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
                ChoiceSection("Theme", WatchTheme.entries.map { it.name }, WatchTheme.fromSavedName(s.theme).name, ::themeName) { t ->
                    save { it.copy(theme = t) }
                }
                ChoiceSection("Library", listOf("GRID", "LIST"), if (s.libraryView == "LIST") "LIST" else "GRID", ::viewName) { v ->
                    save { it.copy(libraryView = v) }
                }
                ChoiceSection("Poster columns", listOf(2, 3), if (s.gridColumns == 2) 2 else 3, { "$it across" }) { n ->
                    save { it.copy(gridColumns = n) }
                }
                ChoiceSection("Skip back / forward", SKIP_STEPS, s.skipSeconds.takeIf { it in SKIP_STEPS } ?: 15, { "$it seconds" }) { n ->
                    save { it.copy(skipSeconds = n) }
                }
                ChoiceSection("Start the player in", ORIENTATIONS, s.startIn.takeIf { it in ORIENTATIONS } ?: "PORTRAIT", ::orientationName) { o ->
                    save { it.copy(startIn = o) }
                }
                ChoiceSection("Subtitle size", SUBTITLE_SIZES, s.subtitleSize.takeIf { it in SUBTITLE_SIZES } ?: "MEDIUM", ::sizeName) { z ->
                    save { it.copy(subtitleSize = z) }
                }
                SectionHeading("About")
                LightText(text = TMDB_CREDIT, variant = LightTextVariant.Detail, lighten = true)
                Spacer(Modifier.height(1.5f.gridUnitsAsDp()))
            }
        }
    }

    private fun save(change: (WatchSettings) -> WatchSettings) = WatchLibrary.inBackground { WatchLibrary.repositories.settings.update(change) }

    private fun themeName(name: String) = if (name == "LIGHT") "Light" else "Dark"

    private fun viewName(name: String) = if (name == "LIST") "List with small posters" else "Poster grid"

    private fun orientationName(name: String) = when (name) {
        "LANDSCAPE" -> "Landscape"
        "LAST" -> "Last used"
        else -> "Portrait"
    }

    private fun sizeName(name: String) = when (name) {
        "SMALL" -> "Small"
        "LARGE" -> "Large"
        "XLARGE" -> "Extra large"
        else -> "Medium"
    }

    private companion object {
        val SKIP_STEPS = listOf(10, 15, 30)
        val ORIENTATIONS = listOf("PORTRAIT", "LANDSCAPE", "LAST")
        val SUBTITLE_SIZES = listOf("SMALL", "MEDIUM", "LARGE", "XLARGE")

        /** Required wording (CLAUDE.md 8). */
        const val TMDB_CREDIT = "This product uses the TMDB API but is not endorsed or certified by TMDB."
    }
}
