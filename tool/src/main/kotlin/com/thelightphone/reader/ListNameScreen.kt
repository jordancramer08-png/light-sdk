package com.thelightphone.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * Types a list's name with the phone's keyboard. Hands the typed name back (tidied, see
 * [cleanListName]); back, or an empty name, hands back nothing.
 */
class ListNameScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initialName: String = "",
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val keyboardOptions = rememberKeyboardOptions()
        val state = rememberTextFieldState(initialName)
        ThemedScreen {
            LightTextInputEditor(
                title = title,
                state = state,
                keyboardOptionsFlow = keyboardOptions,
                onSubmit = { typed -> goBack(cleanListName(typed.toString())) },
                onBack = { goBack() },
                modifier = Modifier.background(LightThemeTokens.colors.background),
                submitLabel = "SAVE",
                singleLine = true,
                initialCaps = true,
            )
        }
    }
}
