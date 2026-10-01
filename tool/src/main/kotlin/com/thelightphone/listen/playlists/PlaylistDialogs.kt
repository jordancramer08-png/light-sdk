package com.thelightphone.listen.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.VolumeKeyScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Types a playlist's name with the phone's keyboard (the Reader's list-name screen). Hands
 * the typed name back, tidied; back, or an empty name, hands back nothing.
 */
class PlaylistNameScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initialName: String = "",
) : VolumeKeyScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val keyboardOptions = rememberKeyboardOptions()
        val state = rememberTextFieldState(initialName)
        ThemedScreen {
            LightTextInputEditor(
                title = title,
                state = state,
                keyboardOptionsFlow = keyboardOptions,
                onSubmit = { typed -> goBack(cleanPlaylistName(typed.toString())) },
                onBack = { goBack() },
                modifier = Modifier.background(LightThemeTokens.colors.background),
                submitLabel = "SAVE",
                singleLine = true,
                initialCaps = true,
            )
        }
    }
}

/** "Delete this playlist?" Hands back true only when DELETE is tapped. */
class DeletePlaylistScreen(
    sealedActivity: SealedLightActivity,
    private val playlistName: String,
) : VolumeKeyScreen<Boolean>(sealedActivity) {

    @Composable
    override fun Content() {
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack(false) }),
                center = LightTopBarCenter.Text(playlistName),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                LightText(
                    text = "Delete this playlist?\n\nThe music stays on your phone.",
                    variant = LightTextVariant.Copy,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LightBottomBar(items = listOf(LightBarButton.Text(text = "DELETE", onClick = { goBack(true) })))
        }
    }
}
