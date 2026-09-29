package com.thelightphone.reader

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

/**
 * Setting rows shared by Reading Settings and the comic viewer's overlay: a stepper
 * ("Label   −  value  +") and an On / Off switch row.
 */

/** Wide enough for the longest value ("Extra large"), so the buttons line up row to row. */
private const val VALUE_WIDTH_GRID_UNITS = 7f

/**
 * "Line spacing    −  Normal  +". A null [onPrevious] / [onNext] means the end of the
 * range: that button is drawn lighter and does nothing. The value's name says where you
 * are, so the lighter tone never carries the meaning alone.
 */
@Composable
fun StepperRow(
    label: String,
    value: String,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    previousSymbol: String = "−",
    nextSymbol: String = "+",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 1f.gridUnitsAsDp(), top = 0.25f.gridUnitsAsDp(), bottom = 0.25f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(text = label, variant = LightTextVariant.Copy, maxLines = 1, modifier = Modifier.weight(1f))
        StepButton(symbol = previousSymbol, onClick = onPrevious)
        LightText(
            text = value,
            variant = LightTextVariant.Copy,
            align = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.width(VALUE_WIDTH_GRID_UNITS.gridUnitsAsDp()),
        )
        StepButton(symbol = nextSymbol, onClick = onNext)
    }
}

@Composable
private fun StepButton(symbol: String, onClick: (() -> Unit)?) {
    LightText(
        text = symbol,
        variant = LightTextVariant.Subtitle,
        lighten = onClick == null,
        align = TextAlign.Center,
        modifier = Modifier
            .lightClickable(onClick = { onClick?.invoke() })
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
    )
}

/**
 * "Progress line      On  (switch)" — tap anywhere on the row to switch. The word says On or
 * Off as well as the switch's shape.
 */
@Composable
fun OnOffRow(label: String, isOn: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(text = label, variant = LightTextVariant.Copy, modifier = Modifier.weight(1f))
        LightText(
            text = if (isOn) "On" else "Off",
            variant = LightTextVariant.Copy,
            lighten = true,
            modifier = Modifier.padding(end = 0.5f.gridUnitsAsDp()),
        )
        LightIcon(icon = if (isOn) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF)
    }
}
