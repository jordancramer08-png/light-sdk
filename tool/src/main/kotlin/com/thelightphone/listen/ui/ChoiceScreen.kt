package com.thelightphone.listen.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Pick one of [choices] (speed, sleep timer), drawn like the sort screen: the [current] one
 * has a filled circle. Tapping a row hands its index back; back changes nothing.
 */
class ChoiceScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val heading: String,
    private val choices: List<String>,
    private val current: Int,
) : VolumeKeyScreen<Int>(sealedActivity) {

    @Composable
    override fun Content() {
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(title),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            LightScrollView(modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
                LightText(
                    text = heading,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                )
                choices.forEachIndexed { i, label ->
                    ChoiceRow(label = label, isCurrent = i == current, onSelect = { goBack(i) })
                    if (i != choices.lastIndex) HairlineDivider()
                }
            }
        }
    }
}
