package com.thelightphone.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

private const val SAMPLE_TEXT =
    "It is a truth universally acknowledged, that a single man in possession of a good " +
        "fortune, must be in want of a wife.\n\n\nHowever little known the feelings or views " +
        "of such a man may be on his first entering a neighbourhood, this truth is so well fixed " +
        "in the minds of the surrounding families, that he is considered as the rightful " +
        "property of some one or other of their daughters."

/**
 * Picks the reading text size. − and + step through the five sizes; the sample below is
 * drawn with exactly the reader's page style, at the same width. Back hands the size to
 * ReaderScreen, which saves it and re-pages the book. The Theme row opens ThemeScreen.
 */
class TextSizeScreen(
    sealedActivity: SealedLightActivity,
    current: ReaderTextSize,
) : SimpleLightScreen<ReaderTextSize>(sealedActivity) {

    private var size by mutableStateOf(current)

    @Composable
    override fun Content() {
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack(size) }),
                center = LightTopBarCenter.Text("Text Size"),
            )
            SizeStepper(
                size = size,
                onSmaller = { size.smaller?.let { size = it } },
                onLarger = { size.larger?.let { size = it } },
            )
            HairlineDivider(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()))
            ThemeRow(onClick = ::openTheme)
            HairlineDivider(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .padding(
                        horizontal = READER_MARGIN_GRID_UNITS.gridUnitsAsDp(),
                        vertical = 1f.gridUnitsAsDp(),
                    ),
            ) {
                BasicText(text = SAMPLE_TEXT, style = readerBodyStyle(size))
            }
        }
    }

    /** The theme applies to every screen as soon as it is picked, so nothing comes back here. */
    private fun openTheme() {
        navigateTo(screenFactory = { ThemeScreen(it) })
    }
}

/** "Theme          Sepia" — tap to open the Theme screen. */
@Composable
private fun ThemeRow(onClick: () -> Unit) {
    val theme by ReaderThemeController.theme.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(text = "Theme", variant = LightTextVariant.Copy, modifier = Modifier.weight(1f))
        LightText(text = theme.label, variant = LightTextVariant.Copy, lighten = true)
    }
}

/**
 * "−   Medium   +". A button at the end of the range is drawn lighter and does nothing;
 * the size name says where you are, so the lighter tone never carries the meaning alone.
 */
@Composable
private fun SizeStepper(size: ReaderTextSize, onSmaller: () -> Unit, onLarger: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(symbol = "−", enabled = size.smaller != null, onClick = onSmaller)
        LightText(
            text = size.label,
            variant = LightTextVariant.Copy,
            align = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        StepButton(symbol = "+", enabled = size.larger != null, onClick = onLarger)
    }
}

@Composable
private fun StepButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    LightText(
        text = symbol,
        variant = LightTextVariant.Subtitle,
        lighten = !enabled,
        align = TextAlign.Center,
        modifier = Modifier
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
    )
}
