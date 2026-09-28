package com.thelightphone.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.thelightphone.reader.data.ReaderThemePreference
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The app's current theme, shared by every screen (like the SDK's LightThemeController,
 * which only knows Dark and Light). Changing it redraws every open screen at once.
 */
object ReaderThemeController {
    private val _theme = MutableStateFlow(ReaderTheme.DEFAULT)
    val theme: StateFlow<ReaderTheme> = _theme.asStateFlow()

    private var loaded = false

    /** Saves outlive the Theme screen, so closing it right after a tap never loses the choice. */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Reads the saved theme once per app launch. */
    suspend fun loadOnce(preference: ReaderThemePreference) {
        if (loaded) return
        loaded = true
        _theme.value = preference.load()
    }

    /** Use [theme] everywhere right away, and remember it. */
    fun choose(theme: ReaderTheme, preference: ReaderThemePreference) {
        loaded = true
        _theme.value = theme
        saveScope.launch { preference.save(theme) }
    }
}

/** The current theme's accent color, for chapter headings and reading progress. */
val LocalReaderAccent = staticCompositionLocalOf { ReaderTheme.DEFAULT.accent }

/**
 * Every screen's outer frame: the current theme's colors and accent, and a full-screen
 * column filled with the theme's background.
 */
@Composable
fun ThemedScreen(content: @Composable ColumnScope.() -> Unit) {
    val theme by ReaderThemeController.theme.collectAsState()
    ThemedWith(theme) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
            content = content,
        )
    }
}

/** Draws [content] in one particular theme (the Theme screen uses it to preview each choice). */
@Composable
fun ThemedWith(theme: ReaderTheme, content: @Composable () -> Unit) {
    LightTheme(colors = theme.colors) {
        CompositionLocalProvider(LocalReaderAccent provides theme.accent, content = content)
    }
}
