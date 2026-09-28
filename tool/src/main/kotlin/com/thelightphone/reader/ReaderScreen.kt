package com.thelightphone.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.viewModelScope
import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.ChapterMeta
import com.thelightphone.reader.data.LibraryStore
import com.thelightphone.reader.data.ReaderDatabase
import com.thelightphone.reader.data.ReaderTextSizePreference
import com.thelightphone.reader.data.ReadingPositionRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
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
    /** Color doesn't change where pages break, so a new theme alone never re-pages the book. */
    fun sameMetricsAs(other: PageLayout) =
        widthPx == other.widthPx && heightPx == other.heightPx && headingGapPx == other.headingGapPx &&
            bodyStyle.withoutColor() == other.bodyStyle.withoutColor() &&
            headingStyle.withoutColor() == other.headingStyle.withoutColor()
}

private fun TextStyle.withoutColor() = copy(color = Color.Unspecified)

/**
 * Pages through one book (CLAUDE.md 7). Each chapter is measured once against the reading
 * area and cut into pages. The place in the book is (chapter, character offset of the
 * page's first character), never a page number, so it survives a change of font or size.
 */
class ReaderScreenViewModel(
    private val bookMeta: BookMeta,
    private val libraryStore: LibraryStore,
    private val readingPositionRepository: ReadingPositionRepository,
    private val textSizePreference: ReaderTextSizePreference,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow<ReaderScreenState>(ReaderScreenState.Loading)
    val state: StateFlow<ReaderScreenState> = _state.asStateFlow()

    /** The reading text size; null until it has been read from storage, so nothing is paged at the wrong size. */
    private val _textSize = MutableStateFlow<ReaderTextSize?>(null)
    val textSize: StateFlow<ReaderTextSize?> = _textSize.asStateFlow()

    private var pageLayout: PageLayout? = null

    private var currentChapterIndex: Int = bookMeta.chapters.firstOrNull()?.index ?: 1
    private var currentText: String = ""
    private var currentPages: List<PageRange> = emptyList()
    private var currentPageIndex: Int = 0

    /**
     * The character the reader is at: the first character of the page they last turned or
     * jumped to. Re-paging (a new text size) keeps it, so changing size back and forth
     * always returns to the same passage instead of creeping backwards.
     */
    private var anchorOffset: Int = 0

    private val chapterTextCache = mutableMapOf<Int, String>()
    private val pageCache = mutableMapOf<Int, List<PageRange>>()
    private var loadJob: Job? = null

    /** Saves run one at a time, in order, so an older place can never overwrite a newer one. */
    private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)

    init {
        viewModelScope.launch { _textSize.value = textSizePreference.load() }
    }

    /** From TextSizeScreen: use the new size for every book, and remember it. */
    fun changeTextSize(size: ReaderTextSize) {
        if (size == _textSize.value) return
        _textSize.value = size
        viewModelScope.launch { textSizePreference.save(size) }
    }

    /** Called once the reading area has a size. Nothing can be paged before that. */
    fun configureLayout(layout: PageLayout) {
        if (layout.widthPx <= 0 || layout.heightPx <= 0) return
        val previous = pageLayout
        pageLayout = layout
        if (previous == null) {
            loadSavedPosition()
        } else if (!layout.sameMetricsAs(previous)) {
            // Page breaks move with the new size, so re-page from the same character.
            pageCache.clear()
            loadChapter(currentChapterIndex, anchorOffset, keepAnchor = true)
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

    /**
     * Reads (or reuses) a chapter's text and pages, then shows the page holding [targetOffset].
     * [keepAnchor] is for re-paging: the reader's place stays [targetOffset] exactly.
     */
    private fun loadChapter(chapterIndex: Int, targetOffset: Int, keepAnchor: Boolean = false) {
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
            if (keepAnchor) anchorOffset = targetOffset
        }
    }

    private fun showPage(pageIndex: Int) {
        currentPageIndex = pageIndex
        val page = currentPages[pageIndex]
        anchorOffset = page.start
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
private const val READER_TOP_BOTTOM_GRID_UNITS = 0.75f
private const val HEADING_GAP_GRID_UNITS = 1f

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

    override fun createViewModel() = ReaderScreenViewModel(
        bookMeta,
        libraryStore,
        readingPositionRepository,
        ReaderTextSizePreference(lightContext.dataStore),
    )

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val textSize by viewModel.textSize.collectAsState()
        val topTitle = (state as? ReaderScreenState.Loaded)?.chapterTitle ?: bookMeta.title

        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(topTitle, onClick = ::openTextSize),
                rightButton = LightBarButton.LightIcon(icon = LightIcons.LIST, onClick = ::openContents),
            )
            // Nothing is drawn until the saved text size is known (a moment at most).
            textSize?.let { size ->
                PageArea(state, size, modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
    }

    private fun openContents() {
        navigateTo(
            screenFactory = { ContentsScreen(it, bookMeta) },
            resultCallback = { chapterIndex -> viewModel.jumpToChapter(chapterIndex) },
        )
    }

    /** Tapping the chapter title opens Text Size; the chosen size comes back when it closes. */
    private fun openTextSize() {
        navigateTo(
            screenFactory = { TextSizeScreen(it, viewModel.textSize.value ?: ReaderTextSize.DEFAULT) },
            resultCallback = { size -> viewModel.changeTextSize(size) },
        )
    }

    /**
     * The page itself. Tap the left 30% to go back a page, anywhere else to go forward.
     * The heading and page are drawn with exactly the styles they were measured with, so
     * every page is filled just as pagination planned.
     */
    @Composable
    private fun PageArea(state: ReaderScreenState, textSize: ReaderTextSize, modifier: Modifier) {
        val textMeasurer = rememberTextMeasurer()
        val bodyStyle = readerBodyStyle(textSize)
        val headingStyle = readerHeadingStyle(textSize)
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
