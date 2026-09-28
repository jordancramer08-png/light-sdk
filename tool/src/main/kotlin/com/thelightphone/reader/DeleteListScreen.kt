package com.thelightphone.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/** "Delete this list?" Hands back true only when DELETE is tapped. */
class DeleteListScreen(
    sealedActivity: SealedLightActivity,
    private val listName: String,
) : SimpleLightScreen<Boolean>(sealedActivity) {

    @Composable
    override fun Content() {
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack(false) }),
                center = LightTopBarCenter.Text(listName),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                LightText(
                    text = "Delete this list?\n\nThe books stay on your phone.",
                    variant = LightTextVariant.Copy,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LightBottomBar(items = listOf(LightBarButton.Text(text = "DELETE", onClick = { goBack(true) })))
        }
    }
}
