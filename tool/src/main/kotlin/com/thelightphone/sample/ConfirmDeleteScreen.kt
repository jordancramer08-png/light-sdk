package com.thelightphone.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * A full-screen "are you sure?" step for a permanent delete. Returns `true`
 * only when Delete is tapped; the back button and Cancel return `false` and
 * change nothing. The caller does the actual deleting when it gets `true`, so a
 * cancel can never touch data.
 *
 * The SDK has no dialog or alert component (checked `sdk/ui/`), so this is a
 * dedicated screen, the same pattern the authenticator example uses for its
 * destructive confirm. [message] should already name what is being deleted and
 * what goes with it, counts included.
 */
class ConfirmDeleteScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val message: String,
) : SimpleLightScreen<Boolean>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack(false) },
                    ),
                    center = LightTopBarCenter.Text(title),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = message,
                        variant = LightTextVariant.Copy,
                        align = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(text = "Cancel", onClick = { goBack(false) }),
                        LightBarButton.Text(text = "Delete", onClick = { goBack(true) }),
                    ),
                )
            }
        }
    }
}
