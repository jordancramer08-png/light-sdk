package com.thelightphone.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.reader.data.ReaderThemePreference
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * Picks the reading theme, reached from the Text Size screen. Each row is drawn in its own
 * theme so you can see it before choosing; the current one has a filled circle. Tapping a
 * row switches every screen to it, remembers it, and goes back.
 */
class ThemeScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    private val preference = ReaderThemePreference(lightContext.dataStore)

    @Composable
    override fun Content() {
        val current by ReaderThemeController.theme.collectAsState()

        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Theme"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
                ReaderTheme.entries.forEachIndexed { i, theme ->
                    ThemeChoiceRow(theme = theme, isCurrent = theme == current, onSelect = { choose(theme) })
                    if (i != ReaderTheme.entries.lastIndex) {
                        HairlineDivider()
                    }
                }
            }
        }
    }

    private fun choose(theme: ReaderTheme) {
        ReaderThemeController.choose(theme, preference)
        goBack()
    }
}

/** One choice, shown in its own colors: its name in body text, "Chapter" in its accent. */
@Composable
private fun ThemeChoiceRow(theme: ReaderTheme, isCurrent: Boolean, onSelect: () -> Unit) {
    ThemedWith(theme) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClick = onSelect)
                .background(LightThemeTokens.colors.background)
                .padding(horizontal = 0.75f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = theme.label,
                variant = LightTextVariant.Copy,
                modifier = Modifier.weight(1f),
            )
            LightText(
                text = "Chapter",
                variant = LightTextVariant.Detail,
                color = LocalReaderAccent.current,
                modifier = Modifier.padding(end = 1f.gridUnitsAsDp()),
            )
            LightIcon(icon = if (isCurrent) LightIcons.SELECT_ON else LightIcons.SELECT_OFF)
        }
    }
}
