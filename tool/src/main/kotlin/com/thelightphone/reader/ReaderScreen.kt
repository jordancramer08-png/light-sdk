package com.thelightphone.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.BookRepository
import com.thelightphone.reader.data.ReaderDatabase
import com.thelightphone.reader.data.ReadingPositionRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * A page's text range within one chapter's full text, in character offsets. Half-open
 * ([start], [endExclusive]) so consecutive pages tile the chapter with no gap or overlap.
 */
private data class PageRange(val start: Int, val endExclusive: Int) {
    operator fun contains(offset: Int) = offset in start until endExclusive
}

sealed interface ReaderScreenState {
    data object Loading : ReaderScreenState
    data class Loaded(
        val chapterTitle: String,
        val pageText: String,
        val isChapterStart: Boolean,
    ) : ReaderScreenState
}

/**
 * Paginates and displays one book. Pagination is per-chapter (CLAUDE.md 6): a chapter's
 * text is measured once against the reading area's real pixel size, then walked line by
 * line into screen-sized pages, cached by chapter index. Position is a (chapter, char
 * offset) pair rather than a page number, since a page number wouldn't survive a font or
 * screen-size change (CLAUDE.md 7) - each page's stored offset is the offset of its first
 * character.
 */
class ReaderScreenViewModel(
    private val bookMeta: BookMeta,
    private val bookRepository: BookRepository,
    private val readingPositionRepository: ReadingPositionRepository,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow<ReaderScreenState>(ReaderScreenState.Loading)
    val state: StateFlow<ReaderScreenState> = _state.asStateFlow()

    private var textMeasurer: TextMeasurer? = null
    private var bodyStyle: TextStyle? = null
    private var headingStyle: TextStyle? = null
    private var headingGapPx: Int = 0
    private var contentWidthPx: Int = 0
    private var contentHeightPx: Int = 0
    private var layoutConfigured = false

    private var currentChapterIndex: Int = bookMeta.chapters.firstOrNull()?.index ?: 1
    private var currentChapterText: String = ""
    private var currentPages: List<PageRange> = emptyList()
    private var currentPageIndex: Int = 0

    private val chapterTextCache = mutableMapOf<Int, String>()
    private val pageCache = mutableMapOf<Int, List<PageRange>>()

    private var loadJob: Job? = null

    /**
     * Called once the reading area's pixel size and text style are known - pagination
     * can't happen before that (CLAUDE.md 6).
     */
    fun configureLayout(
        widthPx: Int,
        heightPx: Int,
        measurer: TextMeasurer,
        style: TextStyle,
        chapterHeadingStyle: TextStyle,
        chapterHeadingGapPx: Int,
    ) {
        val sizeChanged = widthPx != contentWidthPx || heightPx != contentHeightPx
        textMeasurer = measurer
        bodyStyle = style
        headingStyle = chapterHeadingStyle
        headingGapPx = chapterHeadingGapPx
        contentWidthPx = widthPx
        contentHeightPx = heightPx
        if (widthPx <= 0 || heightPx <= 0) return

        if (!layoutConfigured) {
            layoutConfigured = true
            loadInitialPosition()
        } else if (sizeChanged) {
            // Not expected on fixed LP3 hardware, but stay correct: re-paginate from the
            // same character offset, since page boundaries shift with the new metrics.
            val anchorOffset = currentPages.getOrNull(currentPageIndex)?.start ?: 0
            pageCache.clear()
            loadChapter(currentChapterIndex, anchorOffset)
        }
    }

    private fun loadInitialPosition() {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = readingPositionRepository.get(bookMeta.slug)
            val chapterIndex = saved?.chapterIndex ?: bookMeta.chapters.firstOrNull()?.index ?: return@launch
            loadChapter(chapterIndex, saved?.charOffset ?: 0)
        }
    }

    /** ContentsScreen hands back the tapped chapter; jumping always resets to its start (CLAUDE.md 8). */
    fun jumpToChapter(chapterIndex: Int) {
        loadChapter(chapterIndex, targetOffset = 0)
    }

    fun nextPage() {
        val pages = currentPages
        if (pages.isEmpty()) return
        if (currentPageIndex < pages.lastIndex) {
            currentPageIndex++
            publishState()
            savePosition()
        } else {
            val next = bookMeta.chapters.firstOrNull { it.index == currentChapterIndex + 1 } ?: return
            loadChapter(next.index, targetOffset = 0)
        }
    }

    fun previousPage() {
        if (currentPageIndex > 0) {
            currentPageIndex--
            publishState()
            savePosition()
        } else {
            val previous = bookMeta.chapters.firstOrNull { it.index == currentChapterIndex - 1 } ?: return
            loadChapter(previous.index, targetOffset = Int.MAX_VALUE)
        }
    }

    private fun loadChapter(chapterIndex: Int, targetOffset: Int) {
        val measurer = textMeasurer ?: return
        val style = bodyStyle ?: return
        val headingTextStyle = headingStyle ?: return
        if (contentWidthPx <= 0 || contentHeightPx <= 0) return
        val chapterMeta = bookMeta.chapters.firstOrNull { it.index == chapterIndex } ?: return

        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.Default) {
            val text = chapterTextCache.getOrPut(chapterMeta.index) {
                withContext(Dispatchers.IO) {
                    val raw = bookRepository.loadChapterText(bookMeta.slug, chapterMeta.file) ?: ""
                    withExtraParagraphSpacing(raw)
                }
            }
            val pages = pageCache.getOrPut(chapterMeta.index) {
                val headingHeightPx = measurer.measure(
                    text = AnnotatedString(chapterMeta.title),
                    style = headingTextStyle,
                    constraints = Constraints(maxWidth = contentWidthPx),
                ).size.height
                val firstPageHeightPx = (contentHeightPx - headingHeightPx - headingGapPx)
                    .coerceAtLeast(contentHeightPx / 2)
                paginate(measurer, style, text, contentWidthPx, contentHeightPx, firstPageHeightPx)
            }
            val pageIndex = pages.indexOfFirst { targetOffset in it }
                .let { if (it >= 0) it else if (targetOffset <= 0) 0 else pages.lastIndex }
                .coerceIn(0, (pages.size - 1).coerceAtLeast(0))

            currentChapterIndex = chapterMeta.index
            currentChapterText = text
            currentPages = pages
            currentPageIndex = pageIndex
            publishState()
            savePosition()
        }
    }

    private fun publishState() {
        val pages = currentPages
        if (pages.isEmpty()) return
        val page = pages[currentPageIndex]
        val title = bookMeta.chapters.firstOrNull { it.index == currentChapterIndex }?.title ?: bookMeta.title
        _state.value = ReaderScreenState.Loaded(
            chapterTitle = title,
            pageText = currentChapterText.substring(page.start, page.endExclusive),
            isChapterStart = currentPageIndex == 0,
        )
    }

    /** Fire-and-forget - the continuous half of the autosave contract (CLAUDE.md 7). */
    private fun savePosition() {
        val offset = currentPages.getOrNull(currentPageIndex)?.start ?: return
        val chapterIndex = currentChapterIndex
        viewModelScope.launch(Dispatchers.IO) {
            readingPositionRepository.save(bookMeta.slug, chapterIndex, offset, System.currentTimeMillis())
        }
    }

    /**
     * Guaranteed to complete before the screen is torn down, unlike [savePosition]'s
     * coroutine - which races [viewModelScope] cancellation if fired right as the screen
     * is leaving. Room requires off-main-thread access, so this blocks on IO rather than
     * skipping the dispatch (CLAUDE.md 7: position must never be lost).
     */
    private fun flushPosition() {
        val offset = currentPages.getOrNull(currentPageIndex)?.start ?: return
        val chapterIndex = currentChapterIndex
        runBlocking(Dispatchers.IO) {
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

/**
 * Chapter text has paragraphs separated by a single blank line (CLAUDE.md 5). For sustained
 * reading, paragraph breaks read more clearly with a bit more air than a plain line-height
 * gap - so a second blank line is inserted at each break for display/pagination purposes
 * only. This runs once per chapter load and every downstream offset (pagination, saved
 * position) is consistently measured against the resulting string, so nothing needs to
 * translate back to raw file offsets.
 */
private fun withExtraParagraphSpacing(text: String): String = text.replace("\n\n", "\n\n\n")

private fun paginate(
    measurer: TextMeasurer,
    style: TextStyle,
    text: String,
    widthPx: Int,
    heightPx: Int,
    firstPageHeightPx: Int,
): List<PageRange> {
    if (text.isEmpty()) return listOf(PageRange(0, 0))

    val layout = measurer.measure(
        text = AnnotatedString(text),
        style = style,
        constraints = Constraints(maxWidth = widthPx),
    )

    val pages = mutableListOf<PageRange>()
    var lineIndex = 0
    while (lineIndex < layout.lineCount) {
        val pageHeightPx = if (pages.isEmpty()) firstPageHeightPx else heightPx
        val pageTop = layout.getLineTop(lineIndex)
        var lastLine = lineIndex
        while (lastLine + 1 < layout.lineCount &&
            layout.getLineBottom(lastLine + 1) - pageTop <= pageHeightPx.toFloat()
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
private const val READER_MARGIN_GRID_UNITS = 1.5f
private const val HEADING_GAP_GRID_UNITS = 1f
private const val READER_LINE_HEIGHT_MULTIPLIER = 1.45f

class ReaderScreen(
    sealedActivity: SealedLightActivity,
    private val bookMeta: BookMeta,
) : LightScreen<Unit, ReaderScreenViewModel>(sealedActivity) {

    private val bookRepository = BookRepository(lightContext.fileShare)

    private val readingPositionRepository = ReadingPositionRepository.getInstance {
        lightContext.buildDatabase(ReaderDatabase::class.java, ReadingPositionRepository.DATABASE_NAME)
    }

    override val viewModelClass: Class<ReaderScreenViewModel>
        get() = ReaderScreenViewModel::class.java

    override fun createViewModel() = ReaderScreenViewModel(bookMeta, bookRepository, readingPositionRepository)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val textMeasurer = rememberTextMeasurer()
        val bodyStyle = readerBodyStyle()
        val headingStyle = readerHeadingStyle()
        val density = LocalDensity.current
        val headingGapPx = with(density) { HEADING_GAP_GRID_UNITS.gridUnitsAsDp().toPx() }.roundToInt()

        val topTitle = (state as? ReaderScreenState.Loaded)?.chapterTitle ?: bookMeta.title

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text(topTitle),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.LIST,
                        onClick = {
                            navigateTo(
                                screenFactory = { ContentsScreen(it, bookMeta) },
                                resultCallback = { chapterIndex -> viewModel.jumpToChapter(chapterIndex) },
                            )
                        },
                    ),
                )

                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = READER_MARGIN_GRID_UNITS.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp())
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

                    LaunchedEffect(widthPx, heightPx, bodyStyle, headingStyle) {
                        viewModel.configureLayout(widthPx, heightPx, textMeasurer, bodyStyle, headingStyle, headingGapPx)
                    }

                    when (val current = state) {
                        ReaderScreenState.Loading -> Unit
                        is ReaderScreenState.Loaded -> Column(modifier = Modifier.fillMaxSize()) {
                            if (current.isChapterStart) {
                                LightText(
                                    text = current.chapterTitle,
                                    variant = LightTextVariant.Heading,
                                    modifier = Modifier.padding(bottom = HEADING_GAP_GRID_UNITS.gridUnitsAsDp()),
                                )
                            }
                            LightText(
                                text = current.pageText,
                                variant = LightTextVariant.Paragraph,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The same variant style LightText's Paragraph would use, built by hand so the
 * [TextMeasurer] pagination pass measures with the exact style that ends up on screen
 * (LightText's own scaling is `internal` to `:sdk:ui`, unreachable from here). Line height
 * is opened up beyond the base Paragraph variant's for sustained reading comfort.
 */
@Composable
private fun readerBodyStyle(): TextStyle {
    val base = LightThemeTokens.typography.paragraph
    val lineHeight = (base.fontSize.value * READER_LINE_HEIGHT_MULTIPLIER).sp
    return base.copy(
        fontSize = base.fontSize.scaledForReading(),
        lineHeight = lineHeight.scaledForReading(),
        letterSpacing = base.letterSpacing.scaledForReading(),
    )
}

/** Chapter-heading style shown above a chapter's first page, built the same way as [readerBodyStyle]. */
@Composable
private fun readerHeadingStyle(): TextStyle {
    val base = LightThemeTokens.typography.heading
    return base.copy(
        fontSize = base.fontSize.scaledForReading(),
        lineHeight = base.lineHeight.scaledForReading(),
        letterSpacing = base.letterSpacing.scaledForReading(),
    )
}

@Composable
private fun TextUnit.scaledForReading(): TextUnit {
    if (this == TextUnit.Unspecified) return this
    return value.designVerticalPxToSp()
}
