package com.thelightphone.listen.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.storage.Settings
import com.thelightphone.listen.ui.HairlineDivider
import com.thelightphone.listen.ui.ListenTheme
import com.thelightphone.listen.ui.OnOffRow
import com.thelightphone.listen.ui.SettingHeading
import com.thelightphone.listen.ui.SettingNote
import com.thelightphone.listen.ui.StepperRow
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.ValueRow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Theme, how far an audiobook goes back after a pause, and audio offload. Every change is
 * saved at once in /sdcard/Listen/.state/settings.json.
 */
class SettingsScreen(sealedActivity: SealedLightActivity) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val settings by Settings.settings.collectAsState()
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Settings"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ValueRow(
                    label = "Theme",
                    value = ListenTheme.fromSavedName(settings.theme).label,
                    onClick = { navigateTo(screenFactory = { ThemeScreen(it) }) },
                )
                HairlineDivider()

                SettingHeading("Audiobooks: go back after a pause")
                RewindStepper("Short pause", settings.rewindShortSeconds, SHORT_REWINDS) { s ->
                    Settings.change { it.copy(rewindShortSeconds = s) }
                }
                RewindStepper("Long pause", settings.rewindLongSeconds, LONG_REWINDS) { s ->
                    Settings.change { it.copy(rewindLongSeconds = s) }
                }
                SettingNote("A long pause is 10 minutes or more.")
                HairlineDivider()

                SettingHeading("Sound")
                OnOffRow(label = "Audio offload", isOn = settings.audioOffload, onClick = ::toggleOffload)
                SettingNote(OFFLOAD_NOTE)
            }
        }
    }

    private fun toggleOffload() {
        Settings.change { it.copy(audioOffload = !it.audioOffload) }
    }

    private companion object {
        val SHORT_REWINDS = listOf(0, 1, 2, 3, 5, 10, 15)
        val LONG_REWINDS = listOf(0, 5, 10, 15, 20, 30, 45, 60)
        const val OFFLOAD_NOTE =
            "Lets the phone's sound chip play the file, which saves battery on long " +
                "audiobooks. Only used at 1.0× speed. If pausing, seeking or gaps between " +
                "songs misbehave, turn it off. Listen turns it off by itself after a playback error."
    }
}

/** "Short pause   −  3 s  +", stepping through [choices]. */
@Composable
private fun RewindStepper(label: String, seconds: Int, choices: List<Int>, onChange: (Int) -> Unit) {
    // A hand-edited value that isn't one of the choices steps to its nearest neighbours.
    val lower = choices.lastOrNull { it < seconds }
    val higher = choices.firstOrNull { it > seconds }
    StepperRow(
        label = label,
        value = if (seconds <= 0) "Off" else "$seconds s",
        onPrevious = lower?.let { { onChange(it) } },
        onNext = higher?.let { { onChange(it) } },
    )
}
