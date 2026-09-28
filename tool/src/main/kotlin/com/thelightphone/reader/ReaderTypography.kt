package com.thelightphone.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.thelightphone.reader.epub.StyleRange
import com.thelightphone.reader.epub.TextStyleKind
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
 * Left: ragged right, simple (greedy) line breaking, no hyphens — the page the reader always
 * had. Justified: both edges straight, whole-paragraph line breaking and automatic hyphens.
 * Headings have their own style, and scene breaks their own centering, so neither changes.
 * The reader draws each page out of the whole chapter's laid-out text, so a page's lines are
 * exactly the lines that were measured either way.
 */
@Composable
fun readerBodyStyle(settings: ReaderSettings): TextStyle {
    val base = LightThemeTokens.typography.paragraph
    val scale = settings.textSize.scale
    val fontPx = base.fontSize.value * scale
    val justified = settings.alignment == ReaderAlignment.JUSTIFIED
    return base.copy(
        color = LightThemeTokens.colors.content,
        fontFamily = settings.typeface.fontFamily(base.fontFamily),
        fontSize = fontPx.designVerticalPxToSp(),
        lineHeight = (fontPx * settings.lineSpacing.multiplier).designVerticalPxToSp(),
        letterSpacing = base.letterSpacing.scaledForReading(scale),
        textAlign = if (justified) TextAlign.Justify else TextAlign.Start,
        lineBreak = if (justified) LineBreak.Paragraph else LineBreak.Simple,
        hyphens = if (justified) Hyphens.Auto else Hyphens.None,
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

/** How far a block quote is indented, in the text's own size (em). */
private const val QUOTE_INDENT_EM = 1.5f

/**
 * A chapter's text with its italic, bold, block quotes (indented), scene breaks (centered)
 * and note markers (small and raised, each carrying its note as a [NOTE_ANNOTATION]). The
 * reader measures pages with this exact string and draws each page as a piece of it, so what
 * is measured is what is drawn (CLAUDE.md 7). No colors here: the theme's color comes from
 * the body style, and the markers' accent from [withNoteColor].
 */
fun styledChapterText(text: String, styles: List<StyleRange>): AnnotatedString {
    val valid = styles.filter { it.start in 0 until it.end && it.end <= text.length }
    // Compose needs paragraph styles in order and never overlapping.
    var lastParagraphEnd = 0
    val paragraphRanges = valid.filter { it.style.isParagraphStyle() }.sortedBy { it.start }.filter { range ->
        (range.start >= lastParagraphEnd).also { ok -> if (ok) lastParagraphEnd = range.end }
    }
    val spanRanges = valid.filterNot { it.style.isParagraphStyle() }
    val builder = AnnotatedString.Builder(text)
    for (range in spanRanges) builder.addStyle(range.style.spanStyle(), range.start, range.end)
    for (range in paragraphRanges) builder.addStyle(range.style.paragraphStyle(), range.start, range.end)
    // Each note marker carries its note, so a page cut from this text knows its own notes.
    for (range in spanRanges) {
        if (range.style == TextStyleKind.NOTE && range.note != null) {
            builder.addStringAnnotation(NOTE_ANNOTATION, range.note, range.start, range.end)
        }
    }
    return builder.toAnnotatedString()
}

/** The tag of the annotation holding a note marker's note text. */
const val NOTE_ANNOTATION = "note"

/** A note marker's size next to the text around it. */
private const val NOTE_MARKER_EM = 0.7f

private fun TextStyleKind.isParagraphStyle() = this == TextStyleKind.QUOTE || this == TextStyleKind.SCENE_BREAK

private fun TextStyleKind.spanStyle(): SpanStyle = when (this) {
    TextStyleKind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    // Small and raised. Its accent color is added when the page is drawn (withNoteColor),
    // so a theme change never re-pages the book.
    TextStyleKind.NOTE -> SpanStyle(fontSize = NOTE_MARKER_EM.em, baselineShift = BaselineShift.Superscript)
    else -> SpanStyle(fontStyle = FontStyle.Italic)
}

/** [page] with its note markers in [accent]. Color never moves a line break, so pages stay as measured. */
fun withNoteColor(page: AnnotatedString, accent: Color): AnnotatedString {
    val markers = page.getStringAnnotations(NOTE_ANNOTATION, 0, page.length)
    if (markers.isEmpty()) return page
    val builder = AnnotatedString.Builder(page)
    for (marker in markers) builder.addStyle(SpanStyle(color = accent), marker.start, marker.end)
    return builder.toAnnotatedString()
}

private fun TextStyleKind.paragraphStyle(): ParagraphStyle = when (this) {
    TextStyleKind.SCENE_BREAK -> ParagraphStyle(textAlign = TextAlign.Center)
    // The same indent on every line, so a page that starts mid-quote wraps just as it was measured.
    else -> ParagraphStyle(textIndent = TextIndent(firstLine = QUOTE_INDENT_EM.em, restLine = QUOTE_INDENT_EM.em))
}
