package com.thelightphone.listen.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

private const val ROW_ICON_SIZE = 2f
private const val ROW_ICON_PADDING = 0.5f

/** A tappable icon at the end of a list row, with room around it for a thumb (as in the Reader). */
@Composable
fun RowIconButton(icon: LightIconConfiguration, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    LightIcon(
        icon = icon,
        size = ROW_ICON_SIZE,
        contentDescription = label,
        modifier = Modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .lightClickable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .padding(ROW_ICON_PADDING.gridUnitsAsDp()),
    )
}

/** A framed button with an icon and a word, a big tap target (Play, Shuffle). */
@Composable
fun ActionButton(
    label: String,
    icon: LightIconConfiguration,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .height(4f.gridUnitsAsDp())
            .border(1.dp, LightThemeTokens.colors.contentSecondary)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .lightClickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightIcon(icon = icon, size = 1.5f, contentDescription = null)
        Spacer(modifier = Modifier.width(0.5f.gridUnitsAsDp()))
        LightText(text = label, variant = LightTextVariant.Copy)
    }
}

/**
 * A row you tap ([onClick]) or press and hold ([onHold], e.g. "Add to playlist…"). Without
 * [onHold] it's the SDK's plain tap. No press shading, like every Light tap target.
 */
fun Modifier.tapOrHold(onHold: (() -> Unit)?, onClick: () -> Unit): Modifier =
    if (onHold == null) {
        lightClickable(onClick = onClick)
    } else {
        combinedClickable(interactionSource = null, indication = null, onLongClick = onHold, onClick = onClick)
    }

/** How faded something that can't be used right now is drawn (also "not on phone" rows). */
const val DISABLED_ALPHA = 0.35f
