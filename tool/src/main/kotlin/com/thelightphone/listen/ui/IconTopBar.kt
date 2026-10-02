package com.thelightphone.listen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** One icon at the right of an [IconTopBar]; [enabled] = false draws it faded and ignores taps. */
data class TopBarIcon(
    val icon: LightIconConfiguration,
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * A list's top bar with room for more than one icon at the right (the SDK's top bar has
 * one): back, the title, then [icons]. Same height, icon size and spacing as the SDK's bar.
 */
@Composable
fun IconTopBar(title: String, onBack: () -> Unit, icons: List<TopBarIcon>) {
    val height = BAR_GRID_UNITS.gridUnitsAsDp()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
            LightIcon(
                icon = LightIcons.BACK,
                size = ICON_GRID_UNITS,
                contentDescription = "Back",
                modifier = Modifier.lightClickable(onClick = onBack),
            )
            Spacer(modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(ICON_GAP_GRID_UNITS.gridUnitsAsDp())) {
                for (item in icons) {
                    LightIcon(
                        icon = item.icon,
                        size = ICON_GRID_UNITS,
                        contentDescription = item.label,
                        modifier = Modifier
                            .alpha(if (item.enabled) 1f else DISABLED_ALPHA)
                            .lightClickable(enabled = item.enabled, onClickLabel = item.label, onClick = item.onClick),
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            LightText(
                text = title,
                variant = LightTextVariant.Fine,
                align = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = TITLE_MAX_GRID_UNITS.gridUnitsAsDp()),
            )
        }
    }
    Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
}

private const val BAR_GRID_UNITS = 3f
private const val ICON_GRID_UNITS = 2f
private const val ICON_GAP_GRID_UNITS = 1.5f
/** Narrower than the SDK's 18, so the title never runs under the icons. */
private const val TITLE_MAX_GRID_UNITS = 13f
