package com.thelightphone.reader

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

private const val ICON_SIZE = 2f
private const val ICON_PADDING = 0.5f

/** A tappable icon at the end of a list row, with room around it for a thumb. */
@Composable
fun RowIconButton(icon: LightIconConfiguration, onClick: () -> Unit) {
    LightIcon(
        icon = icon,
        size = ICON_SIZE,
        modifier = Modifier
            .lightClickable(onClick = onClick)
            .padding(ICON_PADDING.gridUnitsAsDp()),
    )
}

/** Empty space the size of a [RowIconButton], so the icons after it stay lined up. */
@Composable
fun RowIconGap() {
    Spacer(modifier = Modifier.size((ICON_SIZE + 2 * ICON_PADDING).gridUnitsAsDp()))
}
