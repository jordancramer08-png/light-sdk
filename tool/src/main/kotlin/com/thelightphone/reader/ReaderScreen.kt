package com.thelightphone.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.lifecycle.viewModelScope
import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.ChapterMeta
import com.thelightphone.reader.data.LibraryStore
import com.thelightphone.reader.data.ReaderDatabase
import com.thelightphone.reader.data.ReadingPositionRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

sealed interface ReaderScreenState {
    data object Loading : ReaderScreenState
    data class Loaded(
        val chapterTitle: String,
        val pageText: String,
        val isChapterStart: Boolean,
    ) : ReaderScreenState
}

/**
 * Everything pagination needs to know about the screen: the reading area's size in pixels,
 * and the exact styles the page and heading are drawn with.
 */
data class PageLayout(
    val measurer: TextMeasurer,
    val bodyStyle: TextStyle,
    val headingStyle: TextStyle,
    val headingGapPx: Int,
    val widthPx: Int,
    val heightPx: Int,
) {
    fun sameMetricsAs(other: PageLayout) =
        widthPx == other.widthPx && heightPx == other.heightPx && headingGapPx == other.headingGapPx &&
            bodyStyle == other.bodyStyle && headingStyle == other.headingStyle
}

/**
 * Pages through one book (CLAUDE.md 7). Each chapter is measured once against the reading
 * area and cut into pages. The place in the book is (chapter, character offset of the
 * page's first character), never a page number, so it survives a change of font or size.
 */
class ReaderScreenViewModel(
    private val bookMeta: BookMeta,
    private val libraryStore: LibraryStore,
    private val readingPositionRepository: ReadingPositionRepository,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow<ReaderScreenState>(ReaderScreenState.Loading)
    val state: StateFlow<ReaderScreenState> = _state.asStateFlow()

    private var pageLayout: PageLayout? = null

    private var currentChapterIndex: Int = bookMeta.chapters.firstOrNull()?.index ?: 1
    private var currentText: String = ""
    private var currentPages: List<PageRange> = emptyList()
    private var currentPageIndex: Int = 0

    private val chapterTextCache = mutableMapOf<Int, String>()
    private val pageCache = mutableMapOf<Int, List<PageRange>>()
    private var loadJob: Job? = null

    /** Saves run one at a time, in order, so an older place can never overwrite a newer one. */
    private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Called once the reading area has a size. Nothing can be paged before that. */
    fun configureLayout(layout: PageLayout) {
        if (layout.widthPx <= 0 || layout.heightPx <= 0) return
        val previous = pageLayout
        pageLayout = layout
        if (previous == null) {
            loadSavedPosition()
        } else if (!layout.sameMetricsAs(previous)) {
            // Page breaks move with the new size, so re-page from the same character.
            val anchor = currentPages.getOrNull(currentPageIndex)?.start ?: 0
            pageCache.clear()
            loadChapter(currentChapterIndex, anchor)
        }
    }

    private fun loadSavedPosition() {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { readingPositionRepository.get(bookMeta.slug) }
            if (saved != null && chapter(saved.chapterIndex) != null) {
                loadChapter(saved.chapterIndex, saved.charOffset)
            } else {
                loadChapter(currentChapterIndex, 0)
            }
        }
    }

    /** From ContentsScreen: go to the start of that chapter. */
    fun jumpToChapter(chapterIndex: Int) {
        loadChapter(chapterIndex, 0)
    }

    fun nextPage() {
        if (currentPages.isEmpty()) return
        if (currentPageIndex < currentPages.lastIndex) {
            showPage(currentPageIndex + 1)
        } else {
            chapter(currentChapterIndex + 1)?.let { loadChapter(it.index, 0) }
        }
    }

    fun previousPage() {
        if (currentPages.isEmpty()) return
        if (currentPageIndex > 0) {
            showPage(currentPageIndex - 1)
        } else {
            chapter(currentChapterIndex - 1)?.let { loadChapter(it.index, Int.MAX_VALUE) }
        }
    }

    private fun chapter(index: Int): ChapterMeta? = bookMeta.chapters.firstOrNull { it.index == index }

    /** Reads (or reuses) a chapter's text and pages, then shows the page holding [targetOffset]. */
    private fun loadChapter(chapterIndex: Int, targetOffset: Int) {
        val layout = pageLayout ?: return
        val chapter = chapter(chapterIndex) ?: return

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val text = chapterTextCache.getOrPut(chapter.index) {
                withContext(Dispatchers.IO) {
                    withExtraParagraphSpacing(libraryStore.chapterText(bookMeta, chapter).orEmpty())
                }
            }
            val pages = pageCache.getOrPut(chapter.index) {
                withContext(Dispatchers.Default) { pageChapter(layout, chapter.title, text) }
            }
            currentChapterIndex = chapter.index
            currentText = text
            currentPages = pages
            showPage(pageIndexFor(pages, text, targetOffset))
        }
    }

    private fun showPage(pageIndex: Int) {
        currentPageIndex = pageIndex
        val page = currentPages[pageIndex]
        _state.value = ReaderScreenState.Loaded(
            chapterTitle = chapter(currentChapterIndex)?.title ?: bookMeta.title,
            // Trailing blank lines are part of the page's range but don't need drawing.
            pageText = currentText.substring(page.start, page.endExclusive).trimEnd(),
            isChapterStart = pageIndex == 0,
        )
        savePosition()
    }

    /** Autosave on every page turn. */
    private fun savePosition() {
        val offset = currentPages.getOrNull(currentPageIndex)?.start ?: return
        val chapterIndex = currentChapterIndex
        viewModelScope.launch(saveDispatcher) {
            readingPositionRepository.save(bookMeta.slug, chapterIndex, offset, System.currentTimeMillis())
        }
    }

    /**
     * Waits for the save to finish before the screen goes away, so the place is never lost
     * even if the app is closed right after a page turn.
     */
    private fun flushPosition() {
        val offset = currentPages.getOrNull(currentPageIndex)?.start ?: return
        val chapterIndex = currentChapterIndex
        runBlocking(saveDispatcher) {
            readingPositionRepository.save(bookMeta.slug, chapterIndex, offset, System.currentTimeMillis())
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        flushPosition()
    }

    override fun onAppPause() {
        super.onAppPause()
        flushPosition()
    }
}

/** Measures the chapter with the page's own style and cuts it into pages. */
private fun pageChapter(layout: PageLayout, title: String, text: String): List<PageRange> {
    val constraints = Constraints(maxWidth = layout.widthPx)
    val headingHeightPx = layout.measurer
        .measure(AnnotatedString(title), style = layout.headingStyle, constraints = constraints)
        .size.height
    val firstPageHeightPx = (layout.heightPx - headingHeightPx - layout.headingGapPx)
        .coerceAtLeast(layout.heightPx / 2)
    val body = layout.measurer.measure(AnnotatedString(text), style = layout.bodyStyle, constraints = constraints)
    return paginate(body.lines(text), text.length, layout.heightPx, firstPageHeightPx)
}

private fun TextLayoutResult.lines(text: String): List<TextLine> =
    (0 until lineCount).map { i ->
        TextLine(
            top = getLineTop(i),
            bottom = getLineBottom(i),
            start = getLineStart(i),
            isBlank = text.substring(getLineStart(i), getLineEnd(i)).isBlank(),
        )
    }

private const val BACK_TAP_ZONE_FRACTION = 0.3f
private const val READER_MARGIN_GRID_UNITS = 1.5f
private const val READER_TOP_BOTTOM_GRID_UNITS = 0.75f
private const val HEADING_GAP_GRID_UNITS = 1f
private const val READER_LINE_HEIGHT_MULTIPLIER = 1.45f

class ReaderScreen(
    sealedActivity: SealedLightActivity,
    private val bookMeta: BookMeta,
    private val libraryStore: LibraryStore,
) : LightScreen<Unit, ReaderScreenViewModel>(sealedActivity) {

    private val readingPositionRepository = ReadingPositionRepository.getInstance {
        lightContext.buildDatabase(ReaderDatabase::class.java, ReadingPositionRepository.DATABASE_NAME)
    }

    override val viewModelClass: Class<ReaderScreenViewModel>
        get() = ReaderScreenViewModel::class.java

    override fun createViewModel() = ReaderScreenViewModel(bookMeta, libraryStore, readingPositionRepository)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val topTitle = (state as? ReaderScreenState.Loaded)?.chapterTitle ?: bookMeta.title

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(topTitle),
                    rightButton = LightBarButton.LightIcon(icon = LightIcons.LIST, onClick = ::openContents),
                )
                PageArea(state, modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
    }

    private fun openContents() {
        navigateTo(
            screenFactory = { ContentsScreen(it, bookMeta) },
            resultCallback = { chapterIndex -> viewModel.jumpToChapter(chapterIndex) },
        )
    }

    /**
     * The page itself. Tap the left 30% to go back a page, anywhere else to go forward.
     * The heading and page are drawn with exactly the styles they were measured with, so
     * every page is filled just as pagination planned.
     */
    @Composable
    private fun PageArea(state: ReaderScreenState, modifier: Modifier) {
        val textMeasurer = rememberTextMeasurer()
        val bodyStyle = readerBodyStyle()
        val headingStyle = readerHeadingStyle()
        val density = LocalDensity.current
        val headingGapPx = with(density) { HEADING_GAP_GRID_UNITS.gridUnitsAsDp().toPx() }.roundToInt()
        val headingGap = with(density) { headingGapPx.toDp() }

        BoxWithConstraints(
            modifier = modifier
                .padding(horizontal = READER_MARGIN_GRID_UNITS.gridUnitsAsDp(), vertical = READER_TOP_BOTTOM_GRID_UNITS.gridUnitsAsDp())
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (offset.x < size.width * BACK_TAP_ZONE_FRACTION) viewModel.previousPage() else viewModel.nextPage()
                    }
                },
        ) {
            val widthPx = constraints.maxWidth
            val heightPx = constraints.maxHeight

            LaunchedEffect(widthPx, heightPx, bodyStyle, headingStyle, headingGapPx) {
                viewModel.configureLayout(
                    PageLayout(textMeasurer, bodyStyle, headingStyle, headingGapPx, widthPx, heightPx),
                )
            }

            if (state is ReaderScreenState.Loaded) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (state.isChapterStart) {
                        BasicText(
                            text = state.chapterTitle,
                            style = headingStyle,
                            modifier = Modifier.padding(bottom = headingGap),
                        )
                    }
                    BasicText(text = state.pageText, style = bodyStyle)
                }
            }
        }
    }
}

/**
 * The Paragraph style with more generous line height for long reading. Used both to
 * measure pages and to draw them (CLAUDE.md 7).
 *
 * Line breaking is set to the simple, greedy kind: a page is drawn as its own piece of
 * text, and greedy breaking wraps that piece exactly as it wrapped inside the whole chapter.
 */
@Composable
private fun readerBodyStyle(): TextStyle {
    val base = LightThemeTokens.typography.paragraph
    return base.copy(
        color = LightThemeTokens.colors.content,
        fontSize = base.fontSize.scaledForReading(),
        lineHeight = (base.fontSize.value * READER_LINE_HEIGHT_MULTIPLIER).designVerticalPxToSp(),
        letterSpacing = base.letterSpacing.scaledForReading(),
        lineBreak = LineBreak.Simple,
        hyphens = Hyphens.None,
    )
}

/** The chapter heading on a chapter's first page. Also measured and drawn with this one style. */
@Composable
private fun readerHeadingStyle(): TextStyle {
    val base = LightThemeTokens.typography.heading
    return base.copy(
        color = LightThemeTokens.colors.content,
        fontSize = base.fontSize.scaledForReading(),
        lineHeight = base.lineHeight.scaledForReading(),
        letterSpacing = base.letterSpacing.scaledForReading(),
    )
}

/** Theme sizes are in design pixels; this scales them to the screen, as LightText does. */
@Composable
private fun TextUnit.scaledForReading(): TextUnit {
    if (this == TextUnit.Unspecified) return this
    return value.designVerticalPxToSp()
}
