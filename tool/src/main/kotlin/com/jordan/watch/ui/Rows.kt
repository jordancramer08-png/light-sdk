package com.jordan.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/*
 * Rows shared by Watch's screens, in the Reader's style: a lighter section heading, choice
 * rows with a filled or empty circle (so the current choice reads without color), hairline
 * dividers and plain text action rows.
 */

/** The top bar of every screen below the library: back on the left, [title] in the middle. */
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, right: LightBarButton? = null) {
    LightTopBar(
        leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack),
        center = LightTopBarCenter.Text(title),
        rightButton = right,
    )
}

@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = true,
        maxLines = 1,
        modifier = modifier.padding(top = 1.5f.gridUnitsAsDp(), bottom = 0.25f.gridUnitsAsDp()),
    )
}

/** "Label ......... (o)". Tap to choose it. */
@Composable
fun ChoiceRow(label: String, isCurrent: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().lightClickable(onClick = onSelect).padding(vertical = 0.75f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OneLine(label, LightTextVariant.Copy, Modifier.weight(1f))
        LightIcon(icon = if (isCurrent) LightIcons.SELECT_ON else LightIcons.SELECT_OFF)
    }
}

/** A heading, then one [ChoiceRow] per choice, with hairlines between. */
@Composable
fun <T> ChoiceSection(heading: String, choices: List<T>, current: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SectionHeading(heading)
    choices.forEachIndexed { i, choice ->
        ChoiceRow(label(choice), choice == current) { onSelect(choice) }
        if (i != choices.lastIndex) HairlineDivider()
    }
}

/** "Mark watched   >". A plain tappable row; [detail] is a lighter value on the right. */
@Composable
fun ActionRow(label: String, detail: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().lightClickable(onClick = onClick).padding(vertical = 0.75f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OneLine(label, LightTextVariant.Copy, Modifier.weight(1f))
        if (detail != null) {
            LightText(text = detail, variant = LightTextVariant.Copy, lighten = true, maxLines = 1, modifier = Modifier.padding(end = 0.5f.gridUnitsAsDp()))
        }
        LightIcon(icon = LightIcons.ARROW_RIGHT, size = 1.2f)
    }
}

/** A row of equal text buttons, e.g. "Sort & Filter · Search · Surprise me" under the top bar. */
@Composable
fun ButtonStrip(buttons: List<Pair<String, () -> Unit>>) {
    Row(
        modifier = Modifier.fillMaxWidth().height(2.5f.gridUnitsAsDp()).padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((label, onClick) in buttons) {
            Box(Modifier.weight(1f).height(2.5f.gridUnitsAsDp()).lightClickable(onClick = onClick), contentAlignment = Alignment.Center) {
                LightText(text = label, variant = LightTextVariant.Detail, underline = true, maxLines = 1, align = TextAlign.Center)
            }
        }
    }
}

/**
 * "Skip step    −  15 s  +" (the Reader's StepperRow). A null [onPrevious] / [onNext] is
 * the end of the range: drawn lighter and does nothing; the value says where you are.
 */
@Composable
fun StepperRow(label: String, value: String, onPrevious: (() -> Unit)?, onNext: (() -> Unit)?, previousSymbol: String = "−", nextSymbol: String = "+") {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 0.25f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OneLine(label, LightTextVariant.Copy, Modifier.weight(1f))
        StepButton(previousSymbol, onPrevious)
        LightText(text = value, variant = LightTextVariant.Copy, align = TextAlign.Center, maxLines = 1, modifier = Modifier.width(STEPPER_VALUE_GRID_UNITS.gridUnitsAsDp()))
        StepButton(nextSymbol, onNext)
    }
}

/** Steps through [choices] around [current]; the buttons stop at the ends. */
@Composable
fun <T> ListStepperRow(label: String, choices: List<T>, current: T, name: (T) -> String, onChange: (T) -> Unit) {
    val i = choices.indexOf(current).coerceAtLeast(0)
    StepperRow(
        label = label,
        value = name(current),
        onPrevious = if (i > 0) ({ onChange(choices[i - 1]) }) else null,
        onNext = if (i < choices.lastIndex) ({ onChange(choices[i + 1]) }) else null,
        previousSymbol = "‹",
        nextSymbol = "›",
    )
}

@Composable
private fun StepButton(symbol: String, onClick: (() -> Unit)?) {
    LightText(
        text = symbol,
        variant = LightTextVariant.Subtitle,
        lighten = onClick == null,
        align = TextAlign.Center,
        modifier = Modifier.lightClickable(onClick = { onClick?.invoke() }).padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
    )
}

private const val STEPPER_VALUE_GRID_UNITS = 8f

private const val HAIRLINE_PX = 2f

/** A thin rule in the theme's secondary tone (the Reader's HairlineDivider). */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Spacer(modifier.fillMaxWidth().height(HAIRLINE_PX.designVerticalPxToDp()).background(LightThemeTokens.colors.contentSecondary))
}
