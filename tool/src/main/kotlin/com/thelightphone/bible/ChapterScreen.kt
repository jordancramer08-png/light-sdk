package com.thelightphone.bible

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.thelightphone.bible.data.BibleManifestBook
import com.thelightphone.bible.data.BibleRepository
import com.thelightphone.bible.data.Verse
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * A page's text range within the chapter's full text, in character offsets. Half-open
 * ([start], [endExclusive]) so consecutive pages tile the chapter with no gap or overlap
 * (same approach as the reader project's ReaderScreen.kt, CLAUDE.md 5).
 */
private data class PageRange(val start: Int, val endExclusive: Int)

sealed interface ChapterScreenState {
    data object Loading : ChapterScreenState
    data object Empty : ChapterScreenState
    data object Ready : ChapterScreenState
    data class Loaded(val pageText: AnnotatedString) : ChapterScreenState
}

/**
 * Paginates one chapter's verses. Position is a character offset into the chapter's full
 * text rather than a page number (CLAUDE.md 5), computed fresh each time this screen opens -
 * ChaptersScreen always jumps to a chapter's start, so there's no saved position to restore.
 */
class ChapterScreenViewModel(
    private val bibleRepository: BibleRepository,
    private val translation: String,
    private val book: BibleManifestBook,
    private val chapter: Int,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow<ChapterScreenState>(ChapterScreenState.Loading)
    val state: StateFlow<ChapterScreenState> = _state.asStateFlow()

    private var verses: List<Verse>? = null
    private var chapterText: AnnotatedString? = null
    private var pages: List<PageRange> = emptyList()
    private var pageIndex = 0
    private var layoutConfigured = false

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (verses != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = bibleRepository.loadChapter(translation, book.slug, chapter)
            if (loaded.isNullOrEmpty()) {
                _state.value = ChapterScreenState.Empty
            } else {
                verses = loaded
                _state.value = ChapterScreenState.Ready
            }
        }
    }

    /** Called once the reading area's pixel size and text style are known. */
    fun configureLayout(widthPx: Int, heightPx: Int, measurer: TextMeasurer, style: TextStyle, verseNumberColor: Color) {
        val loadedVerses = verses ?: return
        if (layoutConfigured || widthPx <= 0 || heightPx <= 0) return
        layoutConfigured = true

        val text = buildChapterText(loadedVerses, verseNumberColor)
        chapterText = text
        pages = paginate(measurer, style, text, widthPx, heightPx)
        pageIndex = 0
        publish()
    }

    fun nextPage() {
        if (pageIndex < pages.lastIndex) {
            pageIndex++
            publish()
        }
    }

    fun previousPage() {
        if (pageIndex > 0) {
            pageIndex--
            publish()
        }
    }

    private fun publish() {
        val text = chapterText ?: return
        val page = pages.getOrNull(pageIndex) ?: return
        _state.value = ChapterScreenState.Loaded(text.subSequence(page.start, page.endExclusive))
    }
}

/** Verse numbers rendered inline, lighter than the surrounding text (CLAUDE.md 9). */
private fun buildChapterText(verses: List<Verse>, verseNumberColor: Color): AnnotatedString = buildAnnotatedString {
    verses.forEachIndexed { index, verse ->
        withStyle(SpanStyle(color = verseNumberColor)) { append(verse.number.toString()) }
        append(" ")
        // A poetic line break within a verse is stored as literal " / " (CLAUDE.md 6);
        // render it as an actual line break rather than the separator itself.
        append(verse.text.replace(" / ", "\n"))
        if (index != verses.lastIndex) append(" ")
    }
}

private fun paginate(
    measurer: TextMeasurer,
    style: TextStyle,
    text: AnnotatedString,
    widthPx: Int,
    heightPx: Int,
): List<PageRange> {
    if (text.isEmpty()) return listOf(PageRange(0, 0))

    val layout = measurer.measure(
        text = text,
        style = style,
        constraints = Constraints(maxWidth = widthPx),
    )

    val pages = mutableListOf<PageRange>()
    var lineIndex = 0
    while (lineIndex < layout.lineCount) {
        val pageTop = layout.getLineTop(lineIndex)
        var lastLine = lineIndex
        while (lastLine + 1 < layout.lineCount &&
            layout.getLineBottom(lastLine + 1) - pageTop <= heightPx.toFloat()
        ) {
            lastLine++
        }
        val start = layout.getLineStart(lineIndex)
        val endExclusive = if (lastLine + 1 < layout.lineCount) layout.getLineStart(lastLine + 1) else text.length
        pages.add(PageRange(start, endExclusive))
        lineIndex = lastLine + 1
    }
    return pages.ifEmpty { listOf(PageRange(0, text.length)) }
}

private const val BACK_TAP_ZONE_FRACTION = 0.3f
private const val CHAPTER_MARGIN_GRID_UNITS = 1.5f
private const val CHAPTER_LINE_HEIGHT_MULTIPLIER = 1.45f

class ChapterScreen(
    sealedActivity: SealedLightActivity,
    private val translation: String,
    private val book: BibleManifestBook,
    private val chapter: Int,
) : LightScreen<Unit, ChapterScreenViewModel>(sealedActivity) {

    private val bibleRepository = BibleRepository(lightContext.fileShare)

    override val viewModelClass: Class<ChapterScreenViewModel>
        get() = ChapterScreenViewModel::class.java

    override fun createViewModel() = ChapterScreenViewModel(bibleRepository, translation, book, chapter)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val textMeasurer = rememberTextMeasurer()
        val bodyStyle = chapterBodyStyle()
        val verseNumberColor = LightThemeTokens.colors.contentSecondary
        val density = LocalDensity.current

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("${book.name} $chapter"),
                )

                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = CHAPTER_MARGIN_GRID_UNITS.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp())
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                if (offset.x < size.width * BACK_TAP_ZONE_FRACTION) {
                                    viewModel.previousPage()
                                } else {
                                    viewModel.nextPage()
                                }
                            }
                        },
                ) {
                    val widthPx = with(density) { maxWidth.toPx() }.roundToInt()
                    val heightPx = with(density) { maxHeight.toPx() }.roundToInt()

                    LaunchedEffect(widthPx, heightPx, bodyStyle, verseNumberColor, state) {
                        viewModel.configureLayout(widthPx, heightPx, textMeasurer, bodyStyle, verseNumberColor)
                    }

                    when (val current = state) {
                        ChapterScreenState.Loading, ChapterScreenState.Ready -> Unit
                        ChapterScreenState.Empty -> EmptyChapterMessage()
                        is ChapterScreenState.Loaded -> Text(
                            text = current.pageText,
                            style = bodyStyle,
                            color = LightThemeTokens.colors.content,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChapterMessage() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = "This chapter isn't available yet.",
            variant = LightTextVariant.Copy,
            lighten = true,
            align = TextAlign.Center,
        )
    }
}

/**
 * The Paragraph variant's style, built by hand so the [TextMeasurer] pagination pass
 * measures with the exact style shown on screen. Line height is opened up beyond the base
 * variant's for sustained reading comfort (same approach as the reader project's
 * ReaderScreen.kt, CLAUDE.md 5).
 */
@Composable
private fun chapterBodyStyle(): TextStyle {
    val base = LightThemeTokens.typography.paragraph
    val lineHeight = (base.fontSize.value * CHAPTER_LINE_HEIGHT_MULTIPLIER).sp
    return base.copy(
        fontSize = base.fontSize.scaledForReading(),
        lineHeight = lineHeight.scaledForReading(),
        letterSpacing = base.letterSpacing.scaledForReading(),
    )
}

@Composable
private fun TextUnit.scaledForReading(): TextUnit {
    if (this == TextUnit.Unspecified) return this
    return value.designVerticalPxToSp()
}
