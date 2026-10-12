package com.jordan.watch.ui

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
import com.jordan.watch.storage.WatchLibrary
import com.thelightphone.sdk.ui.LightColors
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * Watch's color themes (the Reader's and Listen's Dark and Light). [accent] is decoration
 * only: progress bars and the chosen tab, which are also marked by position and underline.
 */
enum class WatchTheme(val colors: LightColors, val accent: Color) {
    /** Black, so a movie library doesn't glare. The default. */
    DARK(LightThemeColors.Dark, accent = Color(0xFFE0A860)),
    LIGHT(LightThemeColors.Light, accent = Color(0xFF2B5F8A));

    companion object {
        /** A saved name back to a theme; anything unknown is Dark. */
        fun fromSavedName(name: String?): WatchTheme = entries.firstOrNull { it.name == name } ?: DARK
    }
}

/** The current theme's accent color. */
val LocalAccent = staticCompositionLocalOf { WatchTheme.DARK.accent }

/** Every screen's outer frame: the chosen theme and a full-screen column on its background. */
@Composable
fun ThemedScreen(content: @Composable ColumnScope.() -> Unit) {
    val settings by WatchLibrary.repositories.settings.settings.collectAsState()
    val theme = WatchTheme.fromSavedName(settings.theme)
    LightTheme(colors = theme.colors) {
        CompositionLocalProvider(LocalAccent provides theme.accent) {
            Column(
                modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background),
                content = content,
            )
        }
    }
}
