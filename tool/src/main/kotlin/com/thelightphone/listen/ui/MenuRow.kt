package com.thelightphone.listen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Arrangement
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** A menu row's height (grid units): a big thumb target. */
private const val MENU_ROW_GRID_UNITS = 6f

/**
 * One big row of a menu (Home, Music): [title] with an optional lighter [detail] under it.
 * A null [onClick] means it isn't available yet: the row is drawn lighter and does nothing,
 * and its detail says why.
 */
@Composable
fun MenuRow(title: String, detail: String? = null, onClick: (() -> Unit)?) {
    val tap = if (onClick != null) Modifier.lightClickable(onClick = onClick) else Modifier
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(MENU_ROW_GRID_UNITS.gridUnitsAsDp())
            .then(tap)
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        OneLine(text = title, variant = LightTextVariant.Heading, lighten = onClick == null)
        if (detail != null) OneLine(text = detail, variant = LightTextVariant.Detail, lighten = true)
    }
    HairlineDivider()
}

/** One menu entry of a [SplitMenuRow]. */
data class MenuHalf(val title: String, val detail: String?, val onClick: () -> Unit)

/**
 * Two menu entries side by side in one row (half as much height as two [MenuRow]s), with a
 * thin line between them.
 */
@Composable
fun SplitMenuRow(left: MenuHalf, right: MenuHalf) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SPLIT_ROW_GRID_UNITS.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((i, half) in listOf(left, right).withIndex()) {
            if (i == 1) {
                Spacer(
                    modifier = Modifier
                        .width(HAIRLINE_PX.designVerticalPxToDp())
                        .fillMaxHeight()
                        .padding(vertical = 1f.gridUnitsAsDp())
                        .background(LightThemeTokens.colors.contentSecondary),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .lightClickable(onClick = half.onClick)
                    .padding(horizontal = 1f.gridUnitsAsDp()),
                verticalArrangement = Arrangement.Center,
            ) {
                OneLine(text = half.title, variant = LightTextVariant.Subheading)
                if (half.detail != null) OneLine(text = half.detail, variant = LightTextVariant.Detail, lighten = true)
            }
        }
    }
    HairlineDivider()
}

private const val SPLIT_ROW_GRID_UNITS = 5f
private const val HAIRLINE_PX = 2f
