package com.thelightphone.reader

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * One footnote or endnote, opened by tapping its marker on a page. Drawn in the reader's own
 * text style and margins; a long note scrolls. Back returns to the same page.
 */
class NoteScreen(
    sealedActivity: SealedLightActivity,
    private val note: PageNote,
    private val settings: ReaderSettings,
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Note ${note.marker}"),
            )
            LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
                BasicText(
                    text = withExtraParagraphSpacing(note.text),
                    style = readerBodyStyle(settings),
                    modifier = Modifier.padding(
                        horizontal = settings.margins.gridUnits.gridUnitsAsDp(),
                        vertical = 0.75f.gridUnitsAsDp(),
                    ),
                )
            }
        }
    }
}
