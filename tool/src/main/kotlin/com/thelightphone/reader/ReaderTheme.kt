package com.thelightphone.reader

import androidx.compose.ui.graphics.Color
import com.thelightphone.sdk.ui.LightColors
import com.thelightphone.sdk.ui.LightThemeColors

/**
 * The four reading themes. [colors] is what every screen is drawn with; [accent] colors the
 * chapter heading on a chapter's first page and the "NN% read" text in the library.
 * The accent is decoration only: nothing is shown by color alone.
 */
enum class ReaderTheme(val label: String, val colors: LightColors, val accent: Color) {
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
        fun fromSavedName(name: String?): ReaderTheme =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
