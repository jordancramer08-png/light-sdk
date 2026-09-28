package com.thelightphone.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.TextUnit
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToSp

/**
 * The reading styles, shared by ReaderScreen and the Reading Settings screen's sample so the
 * sample looks exactly like a page. The page's side margins are `settings.margins.gridUnits`.
 */

/**
 * The Paragraph style at the chosen size, typeface and line spacing. Used both to measure
 * pages and to draw them (CLAUDE.md 7).
 *
 * Line breaking is set to the simple, greedy kind: a page is drawn as its own piece of
 * text, and greedy breaking wraps that piece exactly as it wrapped inside the whole chapter.
 */
@Composable
fun readerBodyStyle(settings: ReaderSettings): TextStyle {
    val base = LightThemeTokens.typography.paragraph
    val scale = settings.textSize.scale
    val fontPx = base.fontSize.value * scale
    return base.copy(
        color = LightThemeTokens.colors.content,
        fontFamily = settings.typeface.fontFamily(base.fontFamily),
        fontSize = fontPx.designVerticalPxToSp(),
        lineHeight = (fontPx * settings.lineSpacing.multiplier).designVerticalPxToSp(),
        letterSpacing = base.letterSpacing.scaledForReading(scale),
        lineBreak = LineBreak.Simple,
        hyphens = Hyphens.None,
    )
}

/**
 * The chapter heading on a chapter's first page, grown with the body text, in the same
 * typeface and the theme's accent color. Measured and drawn with this one style.
 */
@Composable
fun readerHeadingStyle(settings: ReaderSettings): TextStyle {
    val base = LightThemeTokens.typography.heading
    val scale = settings.textSize.scale
    return base.copy(
        color = LocalReaderAccent.current,
        fontFamily = settings.typeface.fontFamily(base.fontFamily),
        fontSize = base.fontSize.scaledForReading(scale),
        lineHeight = base.lineHeight.scaledForReading(scale),
        letterSpacing = base.letterSpacing.scaledForReading(scale),
    )
}

/** Light keeps the SDK's own font ([lightFont]); the others are the phone's built-in fonts, no files needed. */
private fun ReaderTypeface.fontFamily(lightFont: FontFamily?): FontFamily? = when (this) {
    ReaderTypeface.LIGHT -> lightFont
    ReaderTypeface.SERIF -> FontFamily.Serif
    ReaderTypeface.SANS -> FontFamily.SansSerif
}

/** Theme sizes are in design pixels; this scales them to the screen, as LightText does, times [scale]. */
@Composable
private fun TextUnit.scaledForReading(scale: Float): TextUnit {
    if (this == TextUnit.Unspecified) return this
    return (value * scale).designVerticalPxToSp()
}
