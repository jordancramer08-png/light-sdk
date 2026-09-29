package com.thelightphone.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
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

/** The sample under the rows: tall enough for a few lines at the largest size. */
private const val SAMPLE_HEIGHT_GRID_UNITS = 6f

/**
 * Text size, typeface, line spacing, margins and alignment, each stepped with the buttons on
 * its row, and the progress line switched on or off.
 * The rows scroll; the sample fixed below them is drawn with exactly the reader's page style
 * and margins, so every change shows at once. Back hands the settings to ReaderScreen, which saves them and
 * re-pages the book. The Theme row opens ThemeScreen.
 */
class ReadingSettingsScreen(
    sealedActivity: SealedLightActivity,
    current: ReaderSettings,
) : SimpleLightScreen<ReaderSettings>(sealedActivity) {

    private var settings by mutableStateOf(current)

    @Composable
    override fun Content() {
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack(settings) }),
                center = LightTopBarCenter.Text("Reading Settings"),
            )
            // The rows scroll; the sample stays put below them, so changes show while scrolling.
            LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SettingRows()
                ThemeRow(onClick = ::openTheme)
            }
            HairlineDivider()
            SamplePreview()
        }
    }

    /** A few lines of text in exactly the reader's page style and margins, cut off at the bottom. */
    @Composable
    private fun SamplePreview() {
        Box(
            modifier = Modifier
                .height(SAMPLE_HEIGHT_GRID_UNITS.gridUnitsAsDp())
                .fillMaxWidth()
                .clipToBounds()
                .padding(
                    start = settings.margins.gridUnits.gridUnitsAsDp(),
                    end = settings.margins.gridUnits.gridUnitsAsDp(),
                    top = 0.75f.gridUnitsAsDp(),
                ),
        ) {
            BasicText(text = SAMPLE_TEXT, style = readerBodyStyle(settings))
        }
    }

    @Composable
    private fun SettingRows() {
        val s = settings
        StepperRow(
            label = "Text size",
            value = s.textSize.label,
            onPrevious = s.textSize.smaller?.let { { settings = s.copy(textSize = it) } },
            onNext = s.textSize.larger?.let { { settings = s.copy(textSize = it) } },
        )
        RowDivider()
        StepperRow(
            label = "Typeface",
            value = s.typeface.label,
            onPrevious = s.typeface.previous?.let { { settings = s.copy(typeface = it) } },
            onNext = s.typeface.next?.let { { settings = s.copy(typeface = it) } },
            previousSymbol = "‹",
            nextSymbol = "›",
        )
        RowDivider()
        StepperRow(
            label = "Line spacing",
            value = s.lineSpacing.label,
            onPrevious = s.lineSpacing.previous?.let { { settings = s.copy(lineSpacing = it) } },
            onNext = s.lineSpacing.next?.let { { settings = s.copy(lineSpacing = it) } },
        )
        RowDivider()
        StepperRow(
            label = "Margins",
            value = s.margins.label,
            onPrevious = s.margins.previous?.let { { settings = s.copy(margins = it) } },
            onNext = s.margins.next?.let { { settings = s.copy(margins = it) } },
        )
        RowDivider()
        StepperRow(
            label = "Alignment",
            value = s.alignment.label,
            onPrevious = s.alignment.previous?.let { { settings = s.copy(alignment = it) } },
            onNext = s.alignment.next?.let { { settings = s.copy(alignment = it) } },
            previousSymbol = "‹",
            nextSymbol = "›",
        )
        RowDivider()
        OnOffRow(
            label = "Progress line",
            isOn = s.showProgressLine,
            onClick = { settings = s.copy(showProgressLine = !s.showProgressLine) },
        )
        RowDivider()
    }

    /** The theme applies to every screen as soon as it is picked, so nothing comes back here. */
    private fun openTheme() {
        navigateTo(screenFactory = { ThemeScreen(it) })
    }
}

@Composable
private fun RowDivider() {
    HairlineDivider(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()))
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
