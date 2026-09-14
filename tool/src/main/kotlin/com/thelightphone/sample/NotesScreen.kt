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
 * Full-screen editor for one lesson's free-text Notes field (CLAUDE.md 5, 6),
 * opened from [LessonScreen]. Stored as just another answer, keyed by
 * [com.thelightphone.sample.data.Lesson.notesId]. Same autosave contract as
 * [AnswerEditorScreen]: a debounced save while typing, plus a flush on every
 * way of leaving the screen, so no route out loses what was typed.
 */
class NotesScreen(
    sealedActivity: SealedLightActivity,
    private val lessonNumber: Int,
    private val notesId: String,
    initialText: String,
    private val answersRepository: AnswersRepository,
) : SimpleLightScreen<Unit>(sealedActivity) {

    private val textFieldState = TextFieldState(initialText)
    private val startedBlank = initialText.isBlank()
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun flushSave() {
        val text = textFieldState.text.toString()
        saveScope.launch {
            answersRepository.save(notesId, text, System.currentTimeMillis())
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
                        answersRepository.save(notesId, text, System.currentTimeMillis())
                    }
                }
        }

        LightTheme(colors = themeColors) {
            LightTextInputEditor(
                title = "Lesson $lessonNumber Notes",
                state = textFieldState,
                keyboardOptionsFlow = keyboardOptionsFlow,
                onSubmit = { goBack() },
                onBack = { goBack() },
                submitLabel = "SAVE",
                initialCaps = startedBlank,
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }
    }
}
