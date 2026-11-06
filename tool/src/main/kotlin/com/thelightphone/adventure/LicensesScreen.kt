package com.thelightphone.adventure

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter

/** Shows the credits and license that must stay with the bundled games. */
class LicensesScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    private val text: String by lazy {
        LICENSE_FILES.joinToString("\n\n") { lightContext.readAsset(it).toString(Charsets.UTF_8) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent) = GameKeys.isIgnored(keyCode)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) = GameKeys.isIgnored(keyCode)

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent) =
        GameKeys.isIgnored(keyCode)

    @Composable
    override fun Content() {
        rememberAppSettings(lightContext.dataStore)
        LightTheme(colors = AdventureTheme.lightColors) {
            Column(Modifier.fillMaxSize().background(AdventureTheme.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Licenses"),
                )
                LightScrollView(Modifier.fillMaxWidth().weight(1f)) {
                    BasicText(
                        text = text,
                        style = TextStyle(
                            color = AdventureTheme.storyText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        ),
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            }
        }
    }

    private companion object {
        val LICENSE_FILES = listOf("CREDITS.txt", "LICENSE-MIT-Microsoft.txt")
    }
}
