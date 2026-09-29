package com.thelightphone.reader

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.reader.data.ComicStore
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** A note file from the comics folder (e.g. "Zatanna's Search (Event Notes)"), as plain scrolling text. */
class ComicNoteScreen(
    sealedActivity: SealedLightActivity,
    private val path: String,
    private val title: String,
    private val store: ComicStore,
) : SimpleLightScreen<Unit>(sealedActivity) {

    /** Null until the file has been read. */
    private var text by mutableStateOf<String?>(null)

    @Composable
    override fun Content() {
        LaunchedEffect(path) {
            text = withContext(Dispatchers.IO) {
                try {
                    store.noteText(path)
                } catch (e: IOException) {
                    "This note can't be read."
                }
            }
        }
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(title),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            text?.let {
                LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LightText(
                        text = it,
                        variant = LightTextVariant.Paragraph,
                        modifier = Modifier.padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}
