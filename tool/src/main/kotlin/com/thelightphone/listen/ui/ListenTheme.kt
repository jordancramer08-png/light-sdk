package com.thelightphone.listen.ui

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
import androidx.compose.ui.graphics.Color
import com.thelightphone.sdk.ui.LightColors
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The four color themes, the same as the Reader's. [colors] is what every screen is drawn
 * with; [accent] is decoration only (nothing is shown by color alone).
 */
enum class ListenTheme(val label: String, val colors: LightColors, val accent: Color) {
    /** The SDK's own dark look: black background, white text. The default. */
    DARK("Dark", LightThemeColors.Dark, accent = Color(0xFFE0A860)),

    /** The SDK's light look: white background, black text. */
    LIGHT("Light", LightThemeColors.Light, accent = Color(0xFF2B5F8A)),

    /** Warm paper with dark brown text. */
    SEPIA(
        "Sepia",
        LightColors(
            background = Color(0xFFF4ECD8),
            content = Color(0xFF4A3A2C),
            contentSecondary = Color(0xFF85705A),
        ),
        accent = Color(0xFF9C4A1A),
    ),

    /** Dark gray with warm cream text, gentler than white on black at night. */
    NIGHT(
        "Night",
        LightColors(
            background = Color(0xFF262626),
            content = Color(0xFFE8D5B0),
            contentSecondary = Color(0xFFA8977A),
        ),
        accent = Color(0xFFE39B5B),
    );

    companion object {
        val DEFAULT = DARK

        /** Turns a saved name back into a theme; anything unknown falls back to Dark. */
        fun fromSavedName(name: String?): ListenTheme =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * The app's current theme, shared by every screen. Changing it redraws every open screen
 * at once. (Choosing and saving a theme comes with the Settings screen.)
 */
object ListenThemeController {
    private val _theme = MutableStateFlow(ListenTheme.DEFAULT)
    val theme: StateFlow<ListenTheme> = _theme.asStateFlow()

    fun choose(theme: ListenTheme) {
        _theme.value = theme
    }
}

/** The current theme's accent color. */
val LocalListenAccent = staticCompositionLocalOf { ListenTheme.DEFAULT.accent }

/**
 * Every screen's outer frame: the current theme's colors and accent, and a full-screen
 * column filled with the theme's background.
 */
@Composable
fun ThemedScreen(content: @Composable ColumnScope.() -> Unit) {
    val theme by ListenThemeController.theme.collectAsState()
    LightTheme(colors = theme.colors) {
        CompositionLocalProvider(LocalListenAccent provides theme.accent) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
                content = content,
            )
        }
    }
}
