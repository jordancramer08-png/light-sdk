package com.thelightphone.listen.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/* Setting rows, the same as the Reader's: a stepper, an On / Off switch and a "go to" row. */

/** Wide enough for the longest value, so the buttons line up row to row. */
private const val VALUE_WIDTH_GRID_UNITS = 5f

/**
 * "After a short pause    −  3 s  +". A null [onPrevious] / [onNext] means the end of the
 * range: that button is drawn lighter and does nothing.
 */
@Composable
fun StepperRow(label: String, value: String, onPrevious: (() -> Unit)?, onNext: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 1f.gridUnitsAsDp(), top = 0.25f.gridUnitsAsDp(), bottom = 0.25f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OneLine(text = label, variant = LightTextVariant.Copy, modifier = Modifier.weight(1f))
        StepButton(symbol = "−", label = "Less", onClick = onPrevious)
        LightText(
            text = value,
            variant = LightTextVariant.Copy,
            align = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.width(VALUE_WIDTH_GRID_UNITS.gridUnitsAsDp()),
        )
        StepButton(symbol = "+", label = "More", onClick = onNext)
    }
}

@Composable
private fun StepButton(symbol: String, label: String, onClick: (() -> Unit)?) {
    LightText(
        text = symbol,
        variant = LightTextVariant.Subtitle,
        lighten = onClick == null,
        align = TextAlign.Center,
        modifier = Modifier
            .lightClickable(onClickLabel = label, onClick = { onClick?.invoke() })
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
    )
}

/** "Audio offload      Off  (switch)". Tap anywhere on the row to switch. */
@Composable
fun OnOffRow(label: String, isOn: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OneLine(text = label, variant = LightTextVariant.Copy, modifier = Modifier.weight(1f))
        LightText(
            text = if (isOn) "On" else "Off",
            variant = LightTextVariant.Copy,
            lighten = true,
            modifier = Modifier.padding(end = 0.5f.gridUnitsAsDp()),
        )
        LightIcon(icon = if (isOn) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF)
    }
}

/** "Theme          Dark  ›". Tap to open its screen. */
@Composable
fun ValueRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OneLine(text = label, variant = LightTextVariant.Copy, modifier = Modifier.weight(1f))
        LightText(text = "$value  ›", variant = LightTextVariant.Copy, lighten = true, maxLines = 1)
    }
}

/** A small explanation under a setting. */
@Composable
fun SettingNote(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier.padding(start = 1f.gridUnitsAsDp(), end = 1f.gridUnitsAsDp(), bottom = 0.75f.gridUnitsAsDp()),
    )
}

/** A lighter heading over a group of settings ("Sound"). */
@Composable
fun SettingHeading(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = true,
        maxLines = 1,
        modifier = Modifier.padding(start = 1f.gridUnitsAsDp(), top = 1f.gridUnitsAsDp(), bottom = 0.25f.gridUnitsAsDp()),
    )
}
