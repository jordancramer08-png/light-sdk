package com.thelightphone.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.thelightphone.sample.data.AnswersRepository
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val AUTOSAVE_PAUSE_MS = 600L

/**
 * Full-screen answer editor for one item (CLAUDE.md 5, 6). Autosaves while typing
 * (a short pause after each edit) and flushes a final save in [willHide] /
 * [onAppPause], so every way of leaving this screen - the SAVE button, the back
 * button, or the app being backgrounded mid-answer - persists the latest text.
 */
class AnswerEditorScreen(
    sealedActivity: SealedLightActivity,
    private val itemId: String,
    private val title: String,
    initialText: String,
    private val answersRepository: AnswersRepository,
) : SimpleLightScreen<Unit>(sealedActivity) {

    private val textFieldState = TextFieldState(initialText)
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun flushSave() {
        val text = textFieldState.text.toString()
        saveScope.launch {
            answersRepository.save(itemId, text, System.currentTimeMillis())
        }
    }

    override fun willHide() {
        super.willHide()
        flushSave()
    }

    override fun onAppPause() {
        super.onAppPause()
        flushSave()
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LaunchedEffect(Unit) {
            snapshotFlow { textFieldState.text.toString() }
                .drop(1)
                .debounce(AUTOSAVE_PAUSE_MS)
                .collectLatest { text ->
                    withContext(Dispatchers.IO) {
                        answersRepository.save(itemId, text, System.currentTimeMillis())
                    }
                }
        }

        LightTheme(colors = themeColors) {
            LightTextInputEditor(
                title = title,
                state = textFieldState,
                keyboardOptionsFlow = keyboardOptionsFlow,
                onSubmit = { goBack() },
                onBack = { goBack() },
                submitLabel = "SAVE",
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }
    }
}
