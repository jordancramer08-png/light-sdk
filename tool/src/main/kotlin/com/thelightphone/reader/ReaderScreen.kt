package com.thelightphone.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.viewModelScope
import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.ChapterMeta
import com.thelightphone.reader.data.DatabaseQueue
import com.thelightphone.reader.data.LibraryStore
import com.thelightphone.reader.data.readerDatabase
import com.thelightphone.reader.data.ReaderSettingsPreference
import com.thelightphone.reader.data.ReadingPositionRepository
import com.thelightphone.reader.data.ReadingSpeedPreference
import com.thelightphone.reader.data.ReadingStatusRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

sealed interface ReaderScreenState {
    data object Loading : ReaderScreenState

    /** A chapter is taking a while to read and page (a huge one): "Preparing…" shows. */
    data class Preparing(val barTitle: String) : ReaderScreenState

    /**
     * [barTitle] is for the top bar ("BOOK 1: GARDENS OF THE MOON · Chapter One");
     * [chapterTitle] heads the chapter's first page. The page is drawn straight out of the
     * whole chapter's laid-out text ([chapterLayout]): the lines from [pageTopPx] to
     * [pageBottomPx], which hold the characters of [page]. [progress] feeds the progress line.
     */
    data class Loaded(
        val barTitle: String,
        val chapterTitle: String,
        val isChapterStart: Boolean,
        val chapterLayout: TextLayoutResult,
        val page: PageRange,
        val pageTopPx: Float,
        val pageBottomPx: Float,
        val progress: ReadingProgress,
    ) : ReaderScreenState
}

/**
 * Everything pagination needs to know about the screen: the reading area's size in pixels,
 * and the exact styles the page and heading are drawn with. [accent] colors the note markers.
 */
data class PageLayout(
    val measurer: TextMeasurer,
    val bodyStyle: TextStyle,
    val headingStyle: TextStyle,
    val headingGapPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    val accent: Color,
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
    private val readingStatusRepository: ReadingStatusRepository,
    private val settingsPreference: ReaderSettingsPreference,
    private val speedPreference: ReadingSpeedPreference,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow<ReaderScreenState>(ReaderScreenState.Loading)
    val state: StateFlow<ReaderScreenState> = _state.asStateFlow()

    /** The reading settings; null until they have been read from storage, so nothing is paged with the wrong ones. */
    private val _settings = MutableStateFlow<ReaderSettings?>(null)
    val settings: StateFlow<ReaderSettings?> = _settings.asStateFlow()

    private var pageLayout: PageLayout? = null

    /** The chapter being read; Contents opens scrolled to it. */
    var currentChapterIndex: Int = bookMeta.chapters.firstOrNull()?.index ?: 1
        private set
    private var currentText: AnnotatedString = AnnotatedString("")
    private var currentPages: List<PageRange> = emptyList()
    private var currentPageIndex: Int = 0

    /**
     * The character the reader is at: the first character of the page they last turned or
     * jumped to. Re-paging (a new size, typeface, spacing or margin) keeps it, so changing back and forth
     * always returns to the same passage instead of creeping backwards.
     */
    private var anchorOffset: Int = 0

    private val chapterTextCache = mutableMapOf<Int, AnnotatedString>()
    private val pageCache = mutableMapOf<Int, List<PageRange>>()
    private var loadJob: Job? = null

    /**
     * The laid-out text of the last few chapters read, which pages are drawn from. Only a few
     * are kept, since a long chapter's layout is large; the rest are laid out again if revisited.
     */
    private val layoutCache = object : LinkedHashMap<Int, TextLayoutResult>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, TextLayoutResult>) =
            size > LAID_OUT_CHAPTERS_KEPT
    }
    private var currentLayout: TextLayoutResult? = null

    /** Saves run one at a time, in order, so an older place can never overwrite a newer one. */
    private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Jordan's reading speed, characters a minute, for the progress line's time left. */
    private val _charsPerMinute = MutableStateFlow(DEFAULT_CHARS_PER_MINUTE)
    val charsPerMinute: StateFlow<Float> = _charsPerMinute.asStateFlow()

    /** Whether the progress line counts down the chapter or the book; tapping it switches. */
    private val _timeLeftMode = MutableStateFlow(TimeLeftMode.CHAPTER)
    val timeLeftMode: StateFlow<TimeLeftMode> = _timeLeftMode.asStateFlow()

    /** When the page on screen appeared (for timing the reading speed); null while away. */
    private var pageShownAtMs: Long? = null

    init {
        viewModelScope.launch { _settings.value = settingsPreference.load() }
        viewModelScope.launch { _charsPerMinute.value = speedPreference.load() }
        // Opening the book makes it Reading (unless it's already Reading or Finished).
        DatabaseQueue.write { readingStatusRepository.markOpened(bookMeta.slug) }
    }

    fun toggleTimeLeftMode() {
        _timeLeftMode.value = _timeLeftMode.value.other
    }

    /** From ReadingSettingsScreen: use the new settings for every book, and remember them. */
    fun changeSettings(settings: ReaderSettings) {
        if (settings == _settings.value) return
        _settings.value = settings
        viewModelScope.launch { settingsPreference.save(settings) }
    }

    /** Called once the reading area has a size. Nothing can be paged before that. */
    fun configureLayout(layout: PageLayout) {
        if (layout.widthPx <= 0 || layout.heightPx <= 0) return
        val previous = pageLayout
        pageLayout = layout
        if (previous == null) {
            loadSavedPosition()
        } else if (!layout.sameMetricsAs(previous)) {
            // Page breaks move with the new style or width, so re-page from the same character.
            pageCache.clear()
            layoutCache.clear()
            loadChapter(currentChapterIndex, anchorOffset, keepAnchor = true)
        } else if (layout != previous) {
            // Only the colors changed (a new theme): same pages, text laid out again in the new colors.
            layoutCache.clear()
            loadChapter(currentChapterIndex, anchorOffset, keepAnchor = true)
        }
    }

    private fun loadSavedPosition() {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { readingPositionRepository.get(bookMeta.slug) }
            if (saved != null && chapter(saved.chapterIndex) != null) {
                loadChapter(saved.chapterIndex, saved.charOffset, isReopening = true)
            } else {
                loadChapter(currentChapterIndex, 0, isReopening = true)
            }
        }
    }

    /** From ContentsScreen: go to the start of that chapter. */
    fun jumpToChapter(chapterIndex: Int) {
        loadChapter(chapterIndex, 0)
    }

    fun nextPage() {
        if (isLoading() || currentPages.isEmpty()) return
        if (currentPageIndex < currentPages.lastIndex) {
            timePageJustRead()
            showPage(currentPageIndex + 1)
        } else {
            chapter(currentChapterIndex + 1)?.let {
                timePageJustRead()
                loadChapter(it.index, 0)
            }
        }
    }

    /**
     * A forward turn means the page on screen was read: its characters over the time it was
     * up give one speed reading, added to the saved average. Turns under 2 seconds or over
     * 5 minutes don't count (see [pageSpeed]).
     */
    private fun timePageJustRead() {
        val shownAt = pageShownAtMs ?: return
        val page = currentPages.getOrNull(currentPageIndex) ?: return
        val speed = pageSpeed(page.endExclusive - page.start, nowMs() - shownAt) ?: return
        viewModelScope.launch { _charsPerMinute.value = speedPreference.addPage(speed) }
    }

    private fun nowMs() = System.nanoTime() / 1_000_000

    fun previousPage() {
        if (isLoading() || currentPages.isEmpty()) return
        if (currentPageIndex > 0) {
            showPage(currentPageIndex - 1)
        } else {
            chapter(currentChapterIndex - 1)?.let { loadChapter(it.index, Int.MAX_VALUE) }
        }
    }

    /** While a chapter is loading, taps are ignored so they can't land on the old chapter. */
    private fun isLoading() = loadJob?.isActive == true

    private fun chapter(index: Int): ChapterMeta? = bookMeta.chapters.firstOrNull { it.index == index }

    /**
     * Reads (or reuses) a chapter's text and pages, then shows the page holding [targetOffset].
     * [keepAnchor] is for re-paging: the reader's place stays [targetOffset] exactly.
     * [isReopening] is for going back to the saved place when the book opens.
     */
    private fun loadChapter(
        chapterIndex: Int,
        targetOffset: Int,
        keepAnchor: Boolean = false,
        isReopening: Boolean = false,
    ) {
        val layout = pageLayout ?: return
        val chapter = chapter(chapterIndex) ?: return

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // Usually done in a blink. If not, say so rather than show a stale or empty page.
            val slowNotice = launch {
                delay(PREPARING_NOTICE_DELAY_MS)
                _state.value = ReaderScreenState.Preparing(readerBarTitle(chapter))
            }
            val text = chapterTextCache.getOrPut(chapter.index) {
                withContext(Dispatchers.IO) { readStyledChapter(chapter) }
            }
            val body = layoutCache.getOrPut(chapter.index) {
                withContext(Dispatchers.Default) { layOutChapter(layout, text) }
            }
            val pages = pageCache.getOrPut(chapter.index) {
                withContext(Dispatchers.Default) { pageChapter(layout, chapter.title, body) }
            }
            slowNotice.cancel()
            currentChapterIndex = chapter.index
            currentText = text
            currentLayout = body
            currentPages = pages
            // Re-paging or reopening isn't the reader moving, so it never marks the book Finished.
            showPage(pageIndexFor(pages, text.text, targetOffset), movedByReader = !keepAnchor && !isReopening)
            if (keepAnchor) anchorOffset = targetOffset
        }
    }

    /** The chapter's text with its styles, spaced out for the page (see [withExtraParagraphSpacing]). */
    private fun readStyledChapter(chapter: ChapterMeta): AnnotatedString {
        val text = libraryStore.chapterText(bookMeta, chapter).orEmpty()
        val styles = libraryStore.chapterStyles(bookMeta, chapter)
        return styledChapterText(withExtraParagraphSpacing(text), withExtraParagraphSpacing(styles, text))
    }

    /** [movedByReader]: a page turn or a Contents jump, as opposed to reopening or re-paging. */
    private fun showPage(pageIndex: Int, movedByReader: Boolean = true) {
        val body = currentLayout ?: return
        currentPageIndex = pageIndex
        val page = currentPages[pageIndex]
        anchorOffset = page.start
        val chapter = chapter(currentChapterIndex)
        // The page's lines: from its first character's line to its last character's line.
        val lastChar = (page.endExclusive - 1).coerceAtLeast(page.start)
        _state.value = ReaderScreenState.Loaded(
            barTitle = chapter?.let(::readerBarTitle) ?: bookMeta.title,
            chapterTitle = chapter?.title ?: bookMeta.title,
            isChapterStart = pageIndex == 0,
            chapterLayout = body,
            page = page,
            pageTopPx = body.getLineTop(body.getLineForOffset(page.start)),
            pageBottomPx = body.getLineBottom(body.getLineForOffset(lastChar)),
            progress = readingProgress(bookMeta.chapters, currentChapterIndex, page.start, currentText.length),
        )
        pageShownAtMs = nowMs()
        savePosition()
        if (movedByReader && isLastPageOfBook()) {
            DatabaseQueue.write { readingStatusRepository.markFinished(bookMeta.slug) }
        }
    }

    private fun isLastPageOfBook(): Boolean =
        currentChapterIndex == bookMeta.chapters.lastOrNull()?.index &&
            currentPageIndex == currentPages.lastIndex

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

    /** Back on this screen (from Contents, a note, settings): time the page from now. */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (currentPages.isNotEmpty()) pageShownAtMs = nowMs()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        pageShownAtMs = null
        flushPosition()
    }

    override fun onAppPause() {
        super.onAppPause()
        pageShownAtMs = null
        flushPosition()
    }
}

/**
 * Lays out the whole chapter with the page's own style (and the note markers' accent).
 * [text] carries the italics, bold, quotes and scene breaks, since they change where lines
 * wrap. Pages are cut from this layout and drawn from it, so measured is drawn.
 */
private fun layOutChapter(layout: PageLayout, text: AnnotatedString): TextLayoutResult =
    layout.measurer.measure(
        withNoteColor(text, layout.accent),
        style = layout.bodyStyle,
        constraints = Constraints(maxWidth = layout.widthPx),
        // Kept in layoutCache instead; the measurer's own cache would hold extra copies.
        skipCache = true,
    )

/** Cuts the laid-out chapter into pages; the first is shorter by the heading above it. */
private fun pageChapter(layout: PageLayout, title: String, body: TextLayoutResult): List<PageRange> {
    val constraints = Constraints(maxWidth = layout.widthPx)
    val headingHeightPx = layout.measurer
        .measure(AnnotatedString(title), style = layout.headingStyle, constraints = constraints)
        .size.height
    val firstPageHeightPx = (layout.heightPx - headingHeightPx - layout.headingGapPx)
        .coerceAtLeast(layout.heightPx / 2)
    val text = body.layoutInput.text
    return paginate(body.lines(text.text), text.length, layout.heightPx, firstPageHeightPx)
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

/** How long a chapter may take to appear before "Preparing…" is shown. */
private const val PREPARING_NOTICE_DELAY_MS = 300L

private const val BACK_TAP_ZONE_FRACTION = 0.3f
private const val READER_TOP_BOTTOM_GRID_UNITS = 0.75f
private const val HEADING_GAP_GRID_UNITS = 1f
private const val PROGRESS_LINE_BOTTOM_GRID_UNITS = 0.5f

/** How many chapters' laid-out text is kept (the one being read and its neighbours, roughly). */
private const val LAID_OUT_CHAPTERS_KEPT = 3

/** How far from a note marker a tap still opens its note (a finger is wider than a raised number). */
private const val NOTE_REACH_GRID_UNITS = 1.5f

class ReaderScreen(
    sealedActivity: SealedLightActivity,
    private val bookMeta: BookMeta,
    private val libraryStore: LibraryStore,
) : LightScreen<Unit, ReaderScreenViewModel>(sealedActivity) {

    private val readingPositionRepository = ReadingPositionRepository.getInstance { lightContext.readerDatabase() }

    override val viewModelClass: Class<ReaderScreenViewModel>
        get() = ReaderScreenViewModel::class.java

    override fun createViewModel() = ReaderScreenViewModel(
        bookMeta,
        libraryStore,
        readingPositionRepository,
        ReadingStatusRepository.getInstance { lightContext.readerDatabase() },
        ReaderSettingsPreference(lightContext.dataStore),
        ReadingSpeedPreference(lightContext.dataStore),
    )

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val settings by viewModel.settings.collectAsState()
        val charsPerMinute by viewModel.charsPerMinute.collectAsState()
        val timeLeftMode by viewModel.timeLeftMode.collectAsState()
        val topTitle = when (val current = state) {
            is ReaderScreenState.Loaded -> current.barTitle
            is ReaderScreenState.Preparing -> current.barTitle
            ReaderScreenState.Loading -> bookMeta.title
        }

        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(topTitle, onClick = ::openSettings),
                rightButton = LightBarButton.LightIcon(icon = LightIcons.LIST, onClick = ::openContents),
            )
            // Nothing is drawn until the saved settings are known (a moment at most).
            settings?.let { current ->
                // The page takes what's left after the progress line, so the line's height is
                // kept out of every page and the two never overlap.
                PageArea(state, current, modifier = Modifier.weight(1f).fillMaxWidth())
                if (current.showProgressLine) {
                    val text = (state as? ReaderScreenState.Loaded)
                        ?.let { progressLineText(it.progress, charsPerMinute, timeLeftMode) }
                    ProgressLine(text, current.margins, onClick = viewModel::toggleTimeLeftMode)
                }
            }
        }
    }

    private fun openContents() {
        navigateTo(
            screenFactory = { ContentsScreen(it, bookMeta, viewModel.currentChapterIndex) },
            resultCallback = { choice ->
                when (choice) {
                    is ContentsChoice.Chapter -> viewModel.jumpToChapter(choice.index)
                    ContentsChoice.BookRemoved -> goBack() // back to the Library, which drops the book
                }
            },
        )
    }

    /** Tapping the chapter title opens Reading Settings; the chosen settings come back when it closes. */
    private fun openSettings() {
        navigateTo(
            screenFactory = { ReadingSettingsScreen(it, viewModel.settings.value ?: ReaderSettings()) },
            resultCallback = { settings -> viewModel.changeSettings(settings) },
        )
    }

    /** Opens the note whose marker was tapped; back returns to this page. */
    private fun openNote(note: PageNote) {
        navigateTo(screenFactory = { NoteScreen(it, note, viewModel.settings.value ?: ReaderSettings()) })
    }

    /**
     * The page itself. A tap on (or very near) a note marker opens the note; otherwise tap the
     * left 30% to go back a page, anywhere else to go forward.
     * The heading and page are drawn with exactly the styles they were measured with, so
     * every page is filled just as pagination planned.
     */
    @Composable
    private fun PageArea(state: ReaderScreenState, settings: ReaderSettings, modifier: Modifier) {
        val textMeasurer = rememberTextMeasurer()
        val bodyStyle = readerBodyStyle(settings)
        val headingStyle = readerHeadingStyle(settings)
        val accent = LocalReaderAccent.current
        val density = LocalDensity.current
        val headingGapPx = with(density) { HEADING_GAP_GRID_UNITS.gridUnitsAsDp().toPx() }.roundToInt()
        val headingGap = with(density) { headingGapPx.toDp() }
        val noteReachPx = with(density) { NOTE_REACH_GRID_UNITS.gridUnitsAsDp().toPx() }

        // Where the page's text sits on screen, for finding a tapped note marker.
        var bodyTop by remember { mutableFloatStateOf(0f) }
        val currentState by rememberUpdatedState(state)

        fun tappedNote(tap: Offset): PageNote? {
            val loaded = currentState as? ReaderScreenState.Loaded ?: return null
            val layout = loaded.chapterLayout
            val notes = pageNotes(layout.layoutInput.text, loaded.page.start, loaded.page.endExclusive)
            if (notes.isEmpty()) return null
            // The page shows the chapter's layout moved up by pageTopPx.
            val inChapter = tap - Offset(0f, bodyTop) + Offset(0f, loaded.pageTopPx)
            return nearestNote(inChapter, markerBoxes(layout, notes), noteReachPx)
        }

        BoxWithConstraints(
            modifier = modifier
                .padding(horizontal = settings.margins.gridUnits.gridUnitsAsDp(), vertical = READER_TOP_BOTTOM_GRID_UNITS.gridUnitsAsDp())
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val note = tappedNote(offset)
                        when {
                            note != null -> openNote(note)
                            offset.x < size.width * BACK_TAP_ZONE_FRACTION -> viewModel.previousPage()
                            else -> viewModel.nextPage()
                        }
                    }
                },
        ) {
            val widthPx = constraints.maxWidth
            val heightPx = constraints.maxHeight

            LaunchedEffect(widthPx, heightPx, bodyStyle, headingStyle, headingGapPx, accent) {
                viewModel.configureLayout(
                    PageLayout(textMeasurer, bodyStyle, headingStyle, headingGapPx, widthPx, heightPx, accent),
                )
            }

            if (state is ReaderScreenState.Preparing) {
                LightText(
                    text = "Preparing…",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    align = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
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
                    PageText(
                        state,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .onGloballyPositioned { bodyTop = it.positionInParent().y },
                    )
                }
            }
        }
    }
}

/**
 * Draws the page's lines straight from the chapter's layout: moved up so the page's first
 * line is at the top, and cut off below its last line. No second layout, so the lines (their
 * breaks, hyphens and justification) are exactly the ones pagination measured.
 */
@Composable
private fun PageText(state: ReaderScreenState.Loaded, modifier: Modifier) {
    Spacer(
        modifier = modifier
            .clipToBounds()
            .drawBehind {
                clipRect(bottom = state.pageBottomPx - state.pageTopPx) {
                    translate(top = -state.pageTopPx) { drawText(state.chapterLayout) }
                }
            },
    )
}

/**
 * "Ch 5 of 63 · 34% · 12 min left in chapter", small and lighter, one line. Tapping it
 * switches between time left in the chapter and in the book. [text] is null while the page
 * is loading: the line is still there (blank), so the page's height never changes.
 */
@Composable
private fun ProgressLine(text: String?, margins: ReaderMargins, onClick: () -> Unit) {
    LightText(
        text = text ?: " ",
        variant = LightTextVariant.Detail,
        lighten = true,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(
                start = margins.gridUnits.gridUnitsAsDp(),
                end = margins.gridUnits.gridUnitsAsDp(),
                bottom = PROGRESS_LINE_BOTTOM_GRID_UNITS.gridUnitsAsDp(),
            ),
    )
}
