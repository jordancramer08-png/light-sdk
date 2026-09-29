package com.thelightphone.listen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * One row of a long list drawn with `LightLazyScrollView`. That view only draws the rows
 * on screen and works out its scrollbar from one fixed row height, so every row must be
 * exactly [heightGridUnits] tall — divider included. The content sits vertically centered.
 * [modifier] (e.g. a tap) applies to the row above the divider.
 */
@Composable
fun UniformRow(
    heightGridUnits: Float,
    showDivider: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().height(heightGridUnits.gridUnitsAsDp())) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().then(modifier),
            contentAlignment = Alignment.CenterStart,
            content = content,
        )
        if (showDivider) HairlineDivider()
    }
}

private const val HAIRLINE_THICKNESS_PX = 2f

/**
 * A thin structural rule for separating rows and sections, in the theme's secondary
 * tone (the same as de-emphasized text).
 */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(HAIRLINE_THICKNESS_PX.designVerticalPxToDp())
            .background(LightThemeTokens.colors.contentSecondary),
    )
}
