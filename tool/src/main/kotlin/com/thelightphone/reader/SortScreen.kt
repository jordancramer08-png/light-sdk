package com.thelightphone.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * The four library orders (CLAUDE.md 8). The current one has a filled circle, the others an
 * empty one, so the choice reads without color. Tapping one hands it back to LibraryScreen.
 */
class SortScreen(
    sealedActivity: SealedLightActivity,
    private val current: LibrarySort,
) : SimpleLightScreen<LibrarySort>(sealedActivity) {

    @Composable
    override fun Content() {
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Sort by"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
                LibrarySort.entries.forEachIndexed { i, sort ->
                    SortChoiceRow(sort = sort, isCurrent = sort == current, onSelect = { goBack(sort) })
                    if (i != LibrarySort.entries.lastIndex) {
                        HairlineDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SortChoiceRow(sort: LibrarySort, isCurrent: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onSelect)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = sort.label,
            variant = LightTextVariant.Copy,
            modifier = Modifier.weight(1f),
        )
        LightIcon(icon = if (isCurrent) LightIcons.SELECT_ON else LightIcons.SELECT_OFF)
    }
}
