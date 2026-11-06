package com.thelightphone.adventure

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme

/** Temporary root screen until the game shelf in Session 8: one button per bundled game. */
@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    override fun onKeyDown(keyCode: Int, event: KeyEvent) = GameKeys.isIgnored(keyCode)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) = GameKeys.isIgnored(keyCode)

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent) =
        GameKeys.isIgnored(keyCode)

    @Composable
    override fun Content() {
        LightTheme(colors = AdventureTheme.lightColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AdventureTheme.background)
                    .padding(16.dp),
            ) {
                LightText(text = "Adventure", variant = LightTextVariant.Heading)
                BundledGame.entries.forEach { game ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { navigateTo({ GameScreen(it, game) }) }
                            .padding(vertical = 18.dp),
                    ) {
                        LightText(text = game.title, variant = LightTextVariant.Heading)
                    }
                }
            }
        }
    }
}
