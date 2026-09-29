package com.thelightphone.reader

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

/** What can be done to a whole comics folder from its menu. */
enum class FolderAction(val label: String) {
    REMOVE_FROM_PHONE("Remove from phone"),
}

/**
 * A comics folder's menu, opened from the ⋯ in its top bar (CLAUDE.md 12). Tapping a row
 * hands that action back to the folder screen; back hands back nothing.
 */
class ComicFolderMenuScreen(
    sealedActivity: SealedLightActivity,
    private val folderTitle: String,
) : SimpleLightScreen<FolderAction>(sealedActivity) {

    @Composable
    override fun Content() {
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(folderTitle),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            FolderAction.entries.forEachIndexed { i, action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable { goBack(action) }
                        .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LightText(text = action.label, variant = LightTextVariant.Copy, modifier = Modifier.weight(1f))
                    LightIcon(icon = LightIcons.TRASH)
                }
                if (i != FolderAction.entries.lastIndex) HairlineDivider()
            }
        }
    }
}
