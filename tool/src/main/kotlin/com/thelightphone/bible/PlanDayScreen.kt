package com.thelightphone.bible

import android.util.Log
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.thelightphone.bible.data.BibleManifest
import com.thelightphone.bible.data.BibleRepository
import com.thelightphone.bible.data.BookNameResolver
import com.thelightphone.bible.data.ReadingPlanDay
import com.thelightphone.bible.data.ReadingPlanPassage
import com.thelightphone.bible.data.ReadingPlanRepository
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
import kotlin.math.roundToInt

private const val TAG = "PlanDayScreen"

/**
 * A page's text range within the day's full text, in character offsets. Half-open
 * ([start], [endExclusive]) so consecutive pages tile the text with no gap or overlap
 * (same approach as ChapterScreen.kt, CLAUDE.md 5).
 */
private data class DayPageRange(val start: Int, val endExclusive: Int) {
    operator fun contains(offset: Int) = offset in start until endExclusive
}

/**
 * One chapter's worth of verses within a passage, tagged with its chapter number so a
 * multi-chapter passage (e.g. Isaiah 45-48) can mark where a new chapter begins.
 */
private data class ChapterVerses(val chapter: Int, val verses: List<Verse>)

private data class LoadedPassage(val heading: String, val chapters: List<ChapterVerses>)

sealed interface PlanDayScreenState {
    data object Loading : PlanDayScreenState
    data object Ready : PlanDayScreenState
    data object Empty : PlanDayScreenState
    data object Complete : PlanDayScreenState
    data class Loaded(val pageText: AnnotatedString) : PlanDayScreenState
}

/**
 * Paginates one day's full reading: all of [day]'s passages in order, each with its own
 * heading, Psalm 119 days showing only the assigned verses (CLAUDE.md 8, 9). Pagination
 * follows ChapterScreen's approach directly - position is a character offset into the
 * day's combined text, re-paginated if the reading area's size changes.
 */
class PlanDayScreenViewModel(
    private val bibleRepository: BibleRepository,
    private val readingPlanRepository: ReadingPlanRepository,
    private val translation: String,
    private val day: ReadingPlanDay,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow<PlanDayScreenState>(
        if (day.complete) PlanDayScreenState.Complete else PlanDayScreenState.Loading,
    )
    val state: StateFlow<PlanDayScreenState> = _state.asStateFlow()

    private var passages: List<LoadedPassage>? = null
    private var dayText: AnnotatedString? = null
    private var pages: List<DayPageRange> = emptyList()
    private var pageIndex = 0

    private var contentWidthPx = 0
    private var contentHeightPx = 0
    private var layoutConfigured = false

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (day.complete || passages != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = loadPassages()
            if (loaded.isNullOrEmpty()) {
                _state.value = PlanDayScreenState.Empty
            } else {
                passages = loaded
                _state.value = PlanDayScreenState.Ready
            }
        }
    }

    private fun loadPassages(): List<LoadedPassage>? {
        val plan = readingPlanRepository.loadPlan() ?: return null
        val manifest = bibleRepository.loadManifest(translation) ?: return null
        val resolver = BookNameResolver(plan)

        return day.passages.mapNotNull { passage -> loadPassage(passage, manifest, resolver) }
    }

    private fun loadPassage(
        passage: ReadingPlanPassage,
        manifest: BibleManifest,
        resolver: BookNameResolver,
    ): LoadedPassage? {
        val book = bibleRepository.findBook(manifest, resolver, passage.book)
        if (book == null) {
            Log.w(
                TAG,
                "\"${passage.book}\" did not resolve to a book in the ${manifest.displayName} manifest - " +
                    "day ${day.day} passage omitted.",
            )
            return null
        }

        val chapters = (passage.startChapter..passage.endChapter).mapNotNull { chapterNum ->
            val verses = bibleRepository.loadVerseRange(translation, book.slug, chapterNum, passage.startVerse, passage.endVerse)
            if (verses.isNullOrEmpty()) {
                Log.w(TAG, "${book.name} $chapterNum missing or empty - day ${day.day} passage incomplete.")
                null
            } else {
                ChapterVerses(chapterNum, verses)
            }
        }
        if (chapters.isEmpty()) return null

        return LoadedPassage(heading = passageHeading(book.name, passage), chapters = chapters)
    }

    /**
     * Called whenever the reading area's pixel size and text style are known. Re-paginates
     * if the size changes after the first layout, anchored at the current page's start
     * offset, since page boundaries shift with the new metrics (same approach as
     * ChapterScreen.kt, CLAUDE.md 5).
     */
    fun configureLayout(widthPx: Int, heightPx: Int, measurer: TextMeasurer, style: TextStyle, verseNumberColor: Color) {
        val loadedPassages = passages ?: return
        val sizeChanged = widthPx != contentWidthPx || heightPx != contentHeightPx
        contentWidthPx = widthPx
        contentHeightPx = heightPx
        if (widthPx <= 0 || heightPx <= 0) return

        if (!layoutConfigured) {
            layoutConfigured = true
            repaginate(loadedPassages, measurer, style, verseNumberColor, widthPx, heightPx, anchorOffset = 0)
        } else if (sizeChanged) {
            val anchorOffset = pages.getOrNull(pageIndex)?.start ?: 0
            repaginate(loadedPassages, measurer, style, verseNumberColor, widthPx, heightPx, anchorOffset)
        }
    }

    private fun repaginate(
        loadedPassages: List<LoadedPassage>,
        measurer: TextMeasurer,
        style: TextStyle,
        verseNumberColor: Color,
        widthPx: Int,
        heightPx: Int,
        anchorOffset: Int,
    ) {
        val text = dayText ?: buildDayText(loadedPassages, verseNumberColor).also { dayText = it }
        val newPages = paginate(measurer, style, text, widthPx, heightPx)
        pages = newPages
        pageIndex = newPages.indexOfFirst { anchorOffset in it }
            .let { if (it >= 0) it else if (anchorOffset <= 0) 0 else newPages.lastIndex }
            .coerceIn(0, (newPages.size - 1).coerceAtLeast(0))
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
        val text = dayText ?: return
        val page = pages.getOrNull(pageIndex) ?: return
        _state.value = PlanDayScreenState.Loaded(text.subSequence(page.start, page.endExclusive))
    }
}

private fun passageHeading(bookName: String, passage: ReadingPlanPassage): String {
    val chapterPart = if (passage.startChapter == passage.endChapter) {
        "${passage.startChapter}"
    } else {
        "${passage.startChapter}-${passage.endChapter}"
    }
    val versePart = if (passage.startVerse != null && passage.endVerse != null) {
        ":${passage.startVerse}-${passage.endVerse}"
    } else {
        ""
    }
    return "$bookName $chapterPart$versePart"
}

/**
 * Each passage gets a bold heading, then its verses with inline verse numbers (lighter
 * than the text, poetic line breaks rendered as real line breaks - CLAUDE.md 6, 9). A
 * multi-chapter passage marks a chapter change with "chapter:verse" on that chapter's
 * first verse, matching the "119:1" convention the source text itself uses at a
 * within-book chapter transition (CLAUDE.md 7), so a run of chapters doesn't read as if
 * verse numbers silently reset.
 */
private fun buildDayText(passages: List<LoadedPassage>, verseNumberColor: Color): AnnotatedString = buildAnnotatedString {
    passages.forEachIndexed { passageIndex, passage ->
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(passage.heading) }
        append("\n")
        passage.chapters.forEachIndexed { chapterIndex, chapterVerses ->
            chapterVerses.verses.forEachIndexed { verseIndex, verse ->
                val numberLabel = if (chapterIndex > 0 && verseIndex == 0) {
                    "${chapterVerses.chapter}:${verse.number}"
                } else {
                    verse.number.toString()
                }
                withStyle(SpanStyle(color = verseNumberColor)) { append(numberLabel) }
                append(" ")
                append(verse.text.replace(" / ", "\n"))

                val isLastVerseInPassage =
                    chapterIndex == passage.chapters.lastIndex && verseIndex == chapterVerses.verses.lastIndex
                if (!isLastVerseInPassage) append(" ")
            }
        }
        if (passageIndex != passages.lastIndex) append("\n\n")
    }
}

private fun paginate(
    measurer: TextMeasurer,
    style: TextStyle,
    text: AnnotatedString,
    widthPx: Int,
    heightPx: Int,
): List<DayPageRange> {
    if (text.isEmpty()) return listOf(DayPageRange(0, 0))

    val layout = measurer.measure(
        text = text,
        style = style,
        constraints = Constraints(maxWidth = widthPx),
    )

    val pages = mutableListOf<DayPageRange>()
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
        pages.add(DayPageRange(start, endExclusive))
        lineIndex = lastLine + 1
    }
    return pages.ifEmpty { listOf(DayPageRange(0, text.length)) }
}

private const val BACK_TAP_ZONE_FRACTION = 0.3f
private const val PLAN_DAY_MARGIN_GRID_UNITS = 1.5f
private const val PLAN_DAY_LINE_HEIGHT_MULTIPLIER = 1.45f

class PlanDayScreen(
    sealedActivity: SealedLightActivity,
    private val day: ReadingPlanDay,
    private val translation: String,
) : LightScreen<Unit, PlanDayScreenViewModel>(sealedActivity) {

    private val bibleRepository = BibleRepository(lightContext.fileShare)
    private val readingPlanRepository = ReadingPlanRepository(lightContext.fileShare)

    override val viewModelClass: Class<PlanDayScreenViewModel>
        get() = PlanDayScreenViewModel::class.java

    override fun createViewModel() = PlanDayScreenViewModel(bibleRepository, readingPlanRepository, translation, day)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val textMeasurer = rememberTextMeasurer()
        val bodyStyle = planDayBodyStyle()
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
                    center = LightTopBarCenter.Text(day.displayDate),
                )

                if (day.complete) {
                    PlanCompleteMessage(day.label)
                } else {
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(
                                horizontal = PLAN_DAY_MARGIN_GRID_UNITS.gridUnitsAsDp(),
                                vertical = 0.75f.gridUnitsAsDp(),
                            )
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
                            PlanDayScreenState.Loading, PlanDayScreenState.Ready, PlanDayScreenState.Complete -> Unit
                            PlanDayScreenState.Empty -> EmptyDayMessage()
                            is PlanDayScreenState.Loaded -> Text(
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
}

@Composable
private fun PlanCompleteMessage(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = message,
            variant = LightTextVariant.Heading,
            align = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyDayMessage() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = "This day's reading isn't available yet.",
            variant = LightTextVariant.Copy,
            lighten = true,
            align = TextAlign.Center,
        )
    }
}

/**
 * The Paragraph variant's style, built by hand so the [TextMeasurer] pagination pass
 * measures with the exact style shown on screen (same approach as ChapterScreen.kt's
 * chapterBodyStyle, CLAUDE.md 5).
 */
@Composable
private fun planDayBodyStyle(): TextStyle {
    val base = LightThemeTokens.typography.paragraph
    val lineHeight = (base.fontSize.value * PLAN_DAY_LINE_HEIGHT_MULTIPLIER).sp
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
