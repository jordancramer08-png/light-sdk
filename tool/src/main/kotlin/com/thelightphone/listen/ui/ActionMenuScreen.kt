package com.thelightphone.listen.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * What press-and-hold opens: [name] and [what] it is at the top, then one row per action
 * ("Play next", "Add to queue", "Add to playlist…"). Tapping a row hands its index back;
 * back does nothing.
 */
class ActionMenuScreen(
    sealedActivity: SealedLightActivity,
    private val name: String,
    private val what: String,
    private val actions: List<String>,
) : VolumeKeyScreen<Int>(sealedActivity) {

    @Composable
    override fun Content() {
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(what),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            LightScrollView(modifier = Modifier.fillMaxWidth()) {
                OneLine(
                    text = name,
                    variant = LightTextVariant.Subheading,
                    modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()).padding(bottom = 0.75f.gridUnitsAsDp()),
                )
                HairlineDivider()
                actions.forEachIndexed { i, label -> MenuRow(title = label, onClick = { goBack(i) }) }
            }
        }
    }
}
