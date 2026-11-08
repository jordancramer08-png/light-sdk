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

/** The files the Licenses screen shows, in order. */
val LICENSE_FILES = listOf("CREDITS.txt", "LICENSE-MIT-Microsoft.txt")

/** A line this long was wrapped by hand in the file, so the next line may continue it. */
private const val WRAPPED_LINE = 60

/**
 * Joins the hand-wrapped lines of a text file into whole paragraphs, so the phone wraps them once.
 * A bullet ("- ") or an indented row starts a new line, except that an indented line continues a
 * bullet. Underlines made only of "=" are dropped, because they would wrap onto a second row.
 */
fun reflowParagraphs(text: String): String {
    val out = ArrayList<String>()
    var previous = ""
    for (raw in text.replace("\r", "").split('\n')) {
        val line = raw.trimEnd()
        if (line.isNotEmpty() && line.all { it == '=' }) continue
        if (continuesPrevious(previous, line, out.lastOrNull().orEmpty())) {
            out[out.size - 1] = out.last() + " " + line.trim()
        } else {
            out += line
        }
        previous = line
    }
    return out.joinToString("\n")
}

/** Whether [line] carries on [paragraph], whose last file line was [previous]. */
private fun continuesPrevious(previous: String, line: String, paragraph: String): Boolean {
    if (previous.length < WRAPPED_LINE || line.isBlank() || line.startsWith("- ")) return false
    return !line.startsWith(" ") || paragraph.startsWith("- ")
}

/** Shows the credits and license that must stay with the bundled games. */
class LicensesScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    private val text: String by lazy {
        LICENSE_FILES.joinToString("\n\n") { reflowParagraphs(lightContext.readAsset(it).toString(Charsets.UTF_8)) }
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
}
