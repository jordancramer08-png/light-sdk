package com.thelightphone.adventure

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.thelightphone.adventure.engine.ZColor
import com.thelightphone.sdk.ui.LightColors

/** One full set of colors. */
data class ThemePalette(
    val name: String,
    val background: Color,
    val storyText: Color,
    val commandText: Color,
    val statusBackground: Color,
    val statusText: Color,
    val inputBackground: Color,
    val hintText: Color,
    val buttonBackground: Color,
    val quickText: Color,
    val verbText: Color,
    val suggestionText: Color,
)

/** The three color themes. Change them here to restyle the tool. */
object ThemePalettes {
    val lantern = ThemePalette(
        name = "Lantern",
        background = Color(0xFF12161C),
        storyText = Color(0xFFE8E2D4),
        commandText = Color(0xFF7FD6B0),
        statusBackground = Color(0xFF20384A),
        statusText = Color(0xFFFFD58A),
        inputBackground = Color(0xFF1C222B),
        hintText = Color(0xFF8A93A0),
        buttonBackground = Color(0xFF2A323D),
        quickText = Color(0xFF9CC9F5),
        verbText = Color(0xFFFFD58A),
        suggestionText = Color(0xFFF0A8C8),
    )

    val parchment = ThemePalette(
        name = "Parchment",
        background = Color(0xFFF4ECD8),
        storyText = Color(0xFF2E2418),
        commandText = Color(0xFF1F6B4A),
        statusBackground = Color(0xFF6B3F1D),
        statusText = Color(0xFFFFF1D0),
        inputBackground = Color(0xFFE8DCC0),
        hintText = Color(0xFF6E6250),
        buttonBackground = Color(0xFFDCCDAA),
        quickText = Color(0xFF1E4F86),
        verbText = Color(0xFF7A3B10),
        suggestionText = Color(0xFF8A1F5C),
    )

    val deepSea = ThemePalette(
        name = "Deep Sea",
        background = Color(0xFF06202B),
        storyText = Color(0xFFD5EEF2),
        commandText = Color(0xFFFFC66D),
        statusBackground = Color(0xFF0E4A5C),
        statusText = Color(0xFF9BF0E1),
        inputBackground = Color(0xFF0B2F3C),
        hintText = Color(0xFF7FA6B0),
        buttonBackground = Color(0xFF123F4D),
        quickText = Color(0xFF8FE3F5),
        verbText = Color(0xFFFFC66D),
        suggestionText = Color(0xFFC9B6FF),
    )

    val all = listOf(lantern, parchment, deepSea)

    /** An unknown saved name falls back to the first theme. */
    fun byName(name: String): ThemePalette = all.firstOrNull { it.name == name } ?: lantern

    /** The next theme, going back to the first after the last. */
    fun next(name: String): ThemePalette = all[(all.indexOf(byName(name)) + 1) % all.size]
}

/** The colors in use now. Screens read these, so changing [palette] redraws them. */
object AdventureTheme {
    var palette by mutableStateOf(ThemePalettes.lantern)

    val background get() = palette.background
    val storyText get() = palette.storyText
    val commandText get() = palette.commandText
    val statusBackground get() = palette.statusBackground
    val statusText get() = palette.statusText
    val inputBackground get() = palette.inputBackground
    val hintText get() = palette.hintText
    val buttonBackground get() = palette.buttonBackground
    val quickText get() = palette.quickText
    val verbText get() = palette.verbText
    val suggestionText get() = palette.suggestionText

    /** Colors for the SDK's own parts: the top bar, buttons and keyboard. */
    val lightColors: LightColors
        get() = LightColors(background = background, content = storyText, contentSecondary = hintText)

    /** The colors a game can ask for with set_colour. Other values fall back to the theme. */
    private fun gameColor(color: ZColor): Color? = when (color) {
        ZColor.BLACK -> Color(0xFF000000)
        ZColor.RED -> Color(0xFFE0524D)
        ZColor.GREEN -> Color(0xFF5CC46A)
        ZColor.YELLOW -> Color(0xFFE8D14F)
        ZColor.BLUE -> Color(0xFF5B8DEF)
        ZColor.MAGENTA -> Color(0xFFD06BD6)
        ZColor.CYAN -> Color(0xFF52C8D6)
        ZColor.WHITE -> Color(0xFFFFFFFF)
        ZColor.CURRENT, ZColor.DEFAULT -> null
    }

    /** The look of a span: the game's own colors if it set any, otherwise the theme's. */
    fun spanStyle(span: StorySpan, themeText: Color, themeBackground: Color): SpanStyle {
        val text = gameColor(span.foreground) ?: themeText
        val back = gameColor(span.background) ?: themeBackground
        val reverse = span.style and ZStyle.REVERSE != 0
        return SpanStyle(
            color = if (reverse) back else text,
            background = if (reverse) text else back,
            fontWeight = if (span.style and ZStyle.BOLD != 0) FontWeight.Bold else null,
            fontStyle = if (span.style and ZStyle.ITALIC != 0) FontStyle.Italic else null,
            fontFamily = if (span.style and ZStyle.FIXED != 0) FontFamily.Monospace else null,
        )
    }

    fun storyColorFor(kind: SpanKind): Color = if (kind == SpanKind.COMMAND) commandText else storyText
}

/** The text size setting, in sp. The middle choice is the default. */
object TextSizes {
    val choices = listOf(15, 18, 21, 25)
    const val DEFAULT = 18

    /** The next size up, going back to the smallest after the largest. */
    fun next(current: Int): Int {
        val index = choices.indexOf(current)
        return choices[(index + 1) % choices.size]
    }
}
