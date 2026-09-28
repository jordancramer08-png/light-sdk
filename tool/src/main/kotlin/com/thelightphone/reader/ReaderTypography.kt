package com.thelightphone.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.TextUnit
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToSp

/**
 * The reading styles, shared by ReaderScreen and the Text Size screen's sample so the
 * sample looks exactly like a page.
 */

private const val READER_LINE_HEIGHT_MULTIPLIER = 1.45f

/** Left and right margin of the reading area. The Text Size sample uses it too, so it wraps like a page. */
const val READER_MARGIN_GRID_UNITS = 1.5f

/**
 * The Paragraph style at the chosen [size], with more generous line height for long
 * reading. Used both to measure pages and to draw them (CLAUDE.md 7).
 *
 * Line breaking is set to the simple, greedy kind: a page is drawn as its own piece of
 * text, and greedy breaking wraps that piece exactly as it wrapped inside the whole chapter.
 */
@Composable
fun readerBodyStyle(size: ReaderTextSize): TextStyle {
    val base = LightThemeTokens.typography.paragraph
    val fontPx = base.fontSize.value * size.scale
    return base.copy(
        color = LightThemeTokens.colors.content,
        fontSize = fontPx.designVerticalPxToSp(),
        lineHeight = (fontPx * READER_LINE_HEIGHT_MULTIPLIER).designVerticalPxToSp(),
        letterSpacing = base.letterSpacing.scaledForReading(size.scale),
        lineBreak = LineBreak.Simple,
        hyphens = Hyphens.None,
    )
}

/**
 * The chapter heading on a chapter's first page, grown with the body text, in the theme's
 * accent color. Measured and drawn with this one style.
 */
@Composable
fun readerHeadingStyle(size: ReaderTextSize): TextStyle {
    val base = LightThemeTokens.typography.heading
    return base.copy(
        color = LocalReaderAccent.current,
        fontSize = base.fontSize.scaledForReading(size.scale),
        lineHeight = base.lineHeight.scaledForReading(size.scale),
        letterSpacing = base.letterSpacing.scaledForReading(size.scale),
    )
}

/** Theme sizes are in design pixels; this scales them to the screen, as LightText does, times [scale]. */
@Composable
private fun TextUnit.scaledForReading(scale: Float): TextUnit {
    if (this == TextUnit.Unspecified) return this
    return (value * scale).designVerticalPxToSp()
}
