package com.thelightphone.listen.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/** "Are you sure?" with one action button. Hands back true only when [action] is tapped; back hands back nothing. */
class ConfirmScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val question: String,
    private val action: String,
) : VolumeKeyScreen<Boolean>(sealedActivity) {

    @Composable
    override fun Content() {
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(title),
            )
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                LightText(text = question, variant = LightTextVariant.Copy, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            LightBottomBar(items = listOf(LightBarButton.Text(text = action, onClick = { goBack(true) })))
        }
    }
}
