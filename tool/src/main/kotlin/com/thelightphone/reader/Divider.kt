package com.thelightphone.reader

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
