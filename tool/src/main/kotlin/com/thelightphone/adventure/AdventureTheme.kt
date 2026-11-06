package com.thelightphone.adventure

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.thelightphone.adventure.engine.ZColor
import com.thelightphone.sdk.ui.LightColors

/** Every color the game uses, in one place. Change them here to restyle the tool. */
object AdventureTheme {
    val background = Color(0xFF12161C)
    val storyText = Color(0xFFE8E2D4)
    val commandText = Color(0xFF7FD6B0)
    val statusBackground = Color(0xFF20384A)
    val statusText = Color(0xFFFFD58A)
    val inputBackground = Color(0xFF1C222B)
    val hintText = Color(0xFF8A93A0)
    val buttonBackground = Color(0xFF2A323D)
    val quickText = Color(0xFF9CC9F5)
    val verbText = Color(0xFFFFD58A)
    val suggestionText = Color(0xFFF0A8C8)

    /** Colors for the SDK's own parts: the top bar, buttons and keyboard. */
    val lightColors = LightColors(
        background = background,
        content = storyText,
        contentSecondary = hintText,
    )

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
