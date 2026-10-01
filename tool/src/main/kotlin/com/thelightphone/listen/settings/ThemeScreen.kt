package com.thelightphone.listen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.listen.storage.Settings
import com.thelightphone.listen.ui.HairlineDivider
import com.thelightphone.listen.ui.ListenTheme
import com.thelightphone.listen.ui.LocalListenAccent
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.VolumeKeyScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * Picks the color theme, like the Reader's Theme screen: each row is drawn in its own theme
 * so you can see it first; the current one has a filled circle. Tapping one switches every
 * screen to it, saves it in settings.json, and goes back.
 */
class ThemeScreen(sealedActivity: SealedLightActivity) : VolumeKeyScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val settings by Settings.settings.collectAsState()
        val current = ListenTheme.fromSavedName(settings.theme)
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Theme"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
                ListenTheme.entries.forEachIndexed { i, theme ->
                    ThemeChoiceRow(theme = theme, isCurrent = theme == current, onSelect = { choose(theme) })
                    if (i != ListenTheme.entries.lastIndex) HairlineDivider()
                }
            }
        }
    }

    private fun choose(theme: ListenTheme) {
        Settings.change { it.copy(theme = theme.name) }
        goBack()
    }
}

/** One choice in its own colors: its name, and "Now Playing" in its accent. */
@Composable
private fun ThemeChoiceRow(theme: ListenTheme, isCurrent: Boolean, onSelect: () -> Unit) {
    LightTheme(colors = theme.colors) {
        CompositionLocalProvider(LocalListenAccent provides theme.accent) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable(onClick = onSelect)
                    .background(LightThemeTokens.colors.background)
                    .padding(horizontal = 0.75f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightText(text = theme.label, variant = LightTextVariant.Copy, modifier = Modifier.weight(1f))
                LightText(
                    text = "Now Playing",
                    variant = LightTextVariant.Detail,
                    color = LocalListenAccent.current,
                    modifier = Modifier.padding(end = 1f.gridUnitsAsDp()),
                )
                LightIcon(icon = if (isCurrent) LightIcons.SELECT_ON else LightIcons.SELECT_OFF)
            }
        }
    }
}
