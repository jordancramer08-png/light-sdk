package com.thelightphone.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * PLACEHOLDER (phase 0). Proves the toolchain builds, installs and shows up in the
 * LightOS launcher. Replaced by the real library screen in phase 3 (CLAUDE.md 9).
 */
@InitialScreen
class LibraryScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(center = LightTopBarCenter.Text("Library"))
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = "Reader rebuild in progress.",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        align = TextAlign.Center,
                    )
                }
            }
        }
    }
}
