package com.thelightphone.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToDp

private const val HAIRLINE_THICKNESS_PX = 2f

/**
 * A thin structural rule for separating rows and sections. Uses the same secondary
 * tone as other de-emphasized text (CLAUDE.md 1: monochrome screen, so separation
 * comes from position and weight, not a new color meaning).
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
