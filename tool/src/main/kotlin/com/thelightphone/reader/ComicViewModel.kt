package com.thelightphone.reader

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewModelScope
import com.thelightphone.reader.comics.ComicItemKind
import com.thelightphone.reader.comics.ComicReadingMode
import com.thelightphone.reader.comics.ComicViewSettings
import com.thelightphone.reader.comics.OpenComic
import com.thelightphone.reader.comics.PageGeometry
import com.thelightphone.reader.comics.PageLayout
import com.thelightphone.reader.comics.PageTap
import com.thelightphone.reader.comics.PageZoom
import com.thelightphone.reader.comics.PanelMargin
import com.thelightphone.reader.comics.PanelStep
import com.thelightphone.reader.comics.PanelTransition
import com.thelightphone.reader.comics.PixelRect
import com.thelightphone.reader.comics.WHOLE_PAGE_PAUSE_MS
import com.thelightphone.reader.comics.comicSlug
import com.thelightphone.reader.comics.enteringPanel
import com.thelightphone.reader.comics.itemsAfter
import com.thelightphone.reader.comics.nextPanelSpot
import com.thelightphone.reader.comics.openingPageIndex
import com.thelightphone.reader.comics.openingPanelIndex
import com.thelightphone.reader.comics.pageLayout
import com.thelightphone.reader.comics.pageTap
import com.thelightphone.reader.comics.pagesToKeep
import com.thelightphone.reader.comics.panelStep
import com.thelightphone.reader.comics.panelZoom
import com.thelightphone.reader.comics.parentPath
import com.thelightphone.reader.comics.rotatedRegionInPage
import com.thelightphone.reader.comics.savedPanelNumber
import com.thelightphone.reader.comics.sliderPage
import com.thelightphone.reader.data.ComicMeta
import com.thelightphone.reader.data.ComicPageImages
import com.thelightphone.reader.data.ComicPositionRepository
import com.thelightphone.reader.data.ComicReadingModePreference
import com.thelightphone.reader.data.ComicStore
import com.thelightphone.reader.data.ComicViewSettingsPreference
import com.thelightphone.reader.data.DatabaseQueue
import com.thelightphone.reader.data.PagePanels
import com.thelightphone.reader.data.ReadingStatusRepository
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A page's picture fitted to the screen, ready to draw, laid out as [layout] says (already
 * turned for [PageLayout.ROTATED]). [pageWidth] × [pageHeight] is the page's full size.
 */
class ComicPagePicture(
    val pageIndex: Int,
    val image: ImageBitmap,
    val pageWidth: Int,
    val pageHeight: Int,
    val layout: PageLayout,
) {
    private val turned: Boolean get() = layout == PageLayout.ROTATED

    /** The page's full size the way it's shown (a turned page is as tall as the page is wide). */
    val shownWidth: Int get() = if (turned) pageHeight else pageWidth
    val shownHeight: Int get() = if (turned) pageWidth else pageHeight

    fun geometry(screenWidth: Int, screenHeight: Int): PageGeometry =
        PageGeometry(shownWidth, shownHeight, screenWidth, screenHeight, fitHeight = layout == PageLayout.FIT_HEIGHT)
}

/** A sharp picture of the zoomed-in part of a page: [region] in the page's pixels as shown (turned, for [PageLayout.ROTATED]). */
class SharpArea(val pageIndex: Int, val layout: PageLayout, val region: PixelRect, val sampleSize: Int, val image: ImageBitmap)

/**
 * One change of zoom, for the screen: it slides there over [durationMs] (0 = it jumps).
 * Each has a new [id], so the screen knows a new one started.
 */
data class ZoomMove(val id: Int, val durationMs: Int)

data class ComicViewState(
    /** The page shown (0-based); -1 until the saved page has been read. */
    val pageIndex: Int = -1,
    /** Null while the page is being read. */
    val picture: ComicPagePicture? = null,
    /** The page took long to read: "Preparing…". */
    val preparing: Boolean = false,
    /** The page's picture couldn't be read. */
    val failed: Boolean = false,
    val zoom: PageZoom = PageZoom(),
    val move: ZoomMove = ZoomMove(0, durationMs = 0),
    val sharp: SharpArea? = null,
    val overlayShown: Boolean = false,
    val mode: ComicReadingMode = ComicReadingMode.DEFAULT,
    val settings: ComicViewSettings = ComicViewSettings(),
    /** In Panels mode, the panel shown (0-based); -1 while the page is shown whole. */
    val panel: Int = -1,
    /** While the slider is being dragged, the page it points at (0-based); the page itself follows once it rests. */
    val sliderPage: Int? = null,
    /** The reader went on past the last page: the end card ("Finished", Next in folder). */
    val ended: Boolean = false,
    /** What "Next in folder" opens; null when there's nothing after this comic (or not looked for yet). */
    val next: NextInFolder? = null,
    /** True once [next] has been looked for. */
    val nextKnown: Boolean = false,
) {
    val isReady: Boolean get() = pageIndex >= 0

    /** The page number the overlay shows (0-based): where the slider points while it's dragged. */
    val shownPageNumber: Int get() = sliderPage ?: pageIndex
}

/**
 * The panel a page opens on once its panels are known: [pick] turns the panel count into the
 * panel. Until then [saved] (1-based, 0 = whole page) is what's saved as the place.
 */
private class PanelEntry(val saved: Int, val pick: (Int) -> Int)

/** Which sharp picture is being (or was) read ahead: this part of this page, at this detail. */
private data class AheadKey(val pageIndex: Int, val region: PixelRect, val sampleSize: Int)

/**
 * The comic viewer (CLAUDE.md 12). Pages are read from the CBZ, kept open while the comic is,
 * one at a time off the main thread. In memory: the page shown and the next one in the
 * reading direction, each fitted to the screen, and while zoomed one sharp picture of the
 * part on screen. In Panels mode a page's panels are looked for off the main thread too, the
 * next page's ahead of time, and each tap moves the zoom to the next panel; the sharp picture
 * of where the next tap lands is read ahead as well. Work for a page the reader has left
 * (or skipped past with the slider) is dropped. The place (page and panel) is saved on every
 * move and saved before leaving (waiting for it). Going on past the last page shows the end
 * card, which offers the next comic or note in the folder; back hands that to the opener.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComicViewModel(
    private val meta: ComicMeta,
    private val comicStore: ComicStore,
    private val positionRepository: ComicPositionRepository,
    private val statusRepository: ReadingStatusRepository,
    private val modePreference: ComicReadingModePreference,
    private val settingsPreference: ComicViewSettingsPreference,
) : LightViewModel<NextInFolder>() {

    private val slug = comicSlug(meta.path)
    private val comic = OpenComic(comicStore.comicFile(meta.path))
    private val pages = meta.pages
    val pageCount: Int = pages.size

    private val _state = MutableStateFlow(ComicViewState())
    val state: StateFlow<ComicViewState> = _state.asStateFlow()

    /** Pages are read one at a time, so two big pictures are never being decoded at once. */
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Panels are looked for one page at a time, beside the picture being read. */
    private val panelDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Fitted pictures kept: the page shown and its neighbour (main thread only). */
    private val pictures = mutableMapOf<Int, ComicPagePicture>()

    /** Each page's panels once looked for; null when the page couldn't be read (main thread only). */
    private val panelsFound = mutableMapOf<Int, PagePanels?>()

    /** Set while the page shown waits for its panels, to open on the right one. */
    private var pendingEntry: PanelEntry? = null

    /** In Panels mode, the whole page is shown until the next tap (a double-tap). */
    private var wholePageHeld = false

    /** In Panels mode, the last page is shown whole after its last panel: the next tap forward ends the comic. */
    private var endOfComicHeld = false

    /** +1 after going forward, -1 after going back: which neighbour to read ahead. */
    private var direction = 1
    private var screenWidth = 0
    private var screenHeight = 0
    private var moveCount = 0
    private var loadJob: Job? = null
    private var sharpJob: Job? = null
    private var panelJob: Job? = null
    private var sliderJob: Job? = null
    private var nextJob: Job? = null

    /** The pause on the whole page after its last panel, before the next page. */
    private var advanceJob: Job? = null

    /** Panels mode: the sharp picture of where the next tap lands, read ahead (main thread only). */
    private var aheadSharp: SharpArea? = null
    private var aheadKey: AheadKey? = null
    private var aheadJob: Job? = null

    // Only touched on decodeDispatcher: the pages open for sharp pieces (the one shown and the next).
    private val regionDecoders = mutableMapOf<Int, BitmapRegionDecoder?>()

    init {
        // Opening a comic makes it Reading (unless it's already Reading or Finished).
        DatabaseQueue.write { statusRepository.markOpened(slug) }
        viewModelScope.launch {
            val mode = modePreference.load()
            val settings = settingsPreference.load()
            val saved = DatabaseQueue.read { positionRepository.get(slug) }
            _state.update { it.copy(mode = mode, settings = settings) }
            val entry = PanelEntry(saved?.panel ?: 0) { count -> openingPanelIndex(saved?.panel, count) }
            showPage(openingPageIndex(saved?.page, pageCount), movedByReader = false, entry)
        }
    }

    // --- from the screen -------------------------------------------------------------

    /** The page area's size in pixels; pictures are read to fit it. */
    fun setScreenSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || (width == screenWidth && height == screenHeight)) return
        screenWidth = width
        screenHeight = height
        pictures.clear()
        forgetAhead()
        val current = _state.value
        if (current.pageIndex < 0) return
        _state.update { it.copy(picture = null, zoom = PageZoom(), move = nextMove(animated = false), sharp = null) }
        if (current.panel >= 0 && !wholePageHeld && !endOfComicHeld) showPanel(current.panel, animated = false)
        loadPictures(current.pageIndex)
    }

    fun tap(x: Float, y: Float) {
        val current = _state.value
        if (!current.isReady || screenWidth == 0 || current.ended) return
        if (current.overlayShown) {
            hideOverlay()
            return
        }
        val panels = current.mode == ComicReadingMode.PANELS
        // In Panels mode the page is always zoomed onto a panel; taps still move on.
        when (pageTap(x, y, screenWidth, screenHeight, zoomed = !panels && current.zoom.isZoomed)) {
            PageTap.TOGGLE_OVERLAY -> _state.update { it.copy(overlayShown = true) }
            PageTap.PREVIOUS -> if (panels) panelTap(forward = false) else goToPage(current.pageIndex - 1)
            PageTap.NEXT -> if (panels) panelTap(forward = true) else goOn()
            PageTap.NOTHING -> Unit
        }
    }

    fun hideOverlay() {
        _state.update { it.copy(overlayShown = false) }
    }

    /** Full page mode: zoom in at the spot, or fit again. Panels mode: the whole page until the next tap. */
    fun doubleTap(x: Float, y: Float) {
        if (_state.value.ended) return
        if (_state.value.mode == ComicReadingMode.PANELS) {
            showWholePageOrPanel()
            return
        }
        val geometry = geometry() ?: return
        setZoom(geometry.doubleTap(_state.value.zoom, x, y))
    }

    /** A pinch or drag: zoom by [factor] around ([focusX], [focusY]) and move by ([dx], [dy]). Full page mode only. */
    fun transform(focusX: Float, focusY: Float, dx: Float, dy: Float, factor: Float) {
        if (_state.value.mode == ComicReadingMode.PANELS || _state.value.ended) return
        val geometry = geometry() ?: return
        val zoomed = geometry.zoomBy(_state.value.zoom, factor, focusX, focusY)
        setZoom(geometry.panBy(zoomed, dx, dy))
    }

    /** A long press anywhere: Full page ↔ Panels. */
    fun switchMode() {
        val next = if (_state.value.mode == ComicReadingMode.PANELS) ComicReadingMode.FULL_PAGE else ComicReadingMode.PANELS
        setMode(next)
    }

    /** From the overlay's Full page / Panels bar, or a long press. Remembered for every comic. */
    fun setMode(mode: ComicReadingMode) {
        val current = _state.value
        if (mode == current.mode || !current.isReady || current.ended) return
        advanceJob?.cancel()
        wholePageHeld = false
        endOfComicHeld = false
        _state.update { it.copy(mode = mode) }
        viewModelScope.launch { modePreference.save(mode) }
        // A spread is laid out differently in each mode: its picture is read again.
        relayoutPictures()
        if (mode == ComicReadingMode.FULL_PAGE) {
            pendingEntry = null
            panelJob?.cancel()
            forgetAhead()
            _state.update { it.copy(panel = -1) }
            moveZoom(startZoom() ?: PageZoom(), animated = true)
            savePosition()
        } else {
            pendingEntry = PanelEntry(saved = 0) { count -> enteringPanel(count, forward = true) }
            savePosition()
            if (current.pageIndex in panelsFound) enterPanels(animated = true) else lookForPanels(current.pageIndex)
        }
    }

    /**
     * From the overlay's slider: [fraction] 0 is the first page, 1 the last. The page number
     * follows the finger at once; the page itself opens (on its first panel) once the finger
     * rests, so pages dragged past are never read.
     */
    fun jumpToFraction(fraction: Float) {
        val target = sliderPage(fraction, pageCount)
        _state.update { it.copy(sliderPage = target) }
        sliderJob?.cancel()
        sliderJob = viewModelScope.launch {
            delay(SLIDER_REST_MS)
            _state.update { it.copy(sliderPage = null) }
            goToPage(target) { count -> enteringPanel(count, forward = true) }
        }
    }

    fun setPanelMargin(margin: PanelMargin) = changeSettings(_state.value.settings.copy(margin = margin))

    fun setPanelTransition(transition: PanelTransition) = changeSettings(_state.value.settings.copy(transition = transition))

    fun setRotateSpreads(rotate: Boolean) = changeSettings(_state.value.settings.copy(rotateSpreads = rotate))

    /** From the end card: back to the last page. */
    fun leaveEnd() {
        _state.update { it.copy(ended = false) }
    }

    // --- settings --------------------------------------------------------------------

    /** Saves the settings for every comic and shows their effect at once. */
    private fun changeSettings(settings: ComicViewSettings) {
        val old = _state.value.settings
        if (settings == old) return
        _state.update { it.copy(settings = settings) }
        viewModelScope.launch { settingsPreference.save(settings) }
        if (settings.rotateSpreads != old.rotateSpreads) relayoutPictures()
        if (settings.margin != old.margin) {
            forgetAhead()
            // The panel shown is zoomed again with the new margin.
            val current = _state.value
            val onPanel = current.mode == ComicReadingMode.PANELS && current.panel >= 0
            if (onPanel && !wholePageHeld && !endOfComicHeld && advanceJob?.isActive != true) showPanel(current.panel, animated = true)
        }
    }

    /** Pictures laid out for the old mode or setting are read again (a spread's layout changed). */
    private fun relayoutPictures() {
        val index = _state.value.pageIndex
        val stale = pictures.values.filter { it.layout != layoutFor(it.pageWidth, it.pageHeight) }.map { it.pageIndex }
        if (stale.isEmpty() && _state.value.picture != null) return
        pictures.keys.removeAll(stale.toSet())
        if (index in stale) {
            _state.update { it.copy(picture = null, zoom = PageZoom(), move = nextMove(animated = false), sharp = null) }
        }
        loadPictures(index)
    }

    private fun layoutFor(pageWidth: Int, pageHeight: Int): PageLayout =
        pageLayout(pageWidth, pageHeight, _state.value.mode, _state.value.settings.rotateSpreads)

    // --- panels ----------------------------------------------------------------------

    /** Panels mode: a tap on the right ([forward]) or left side. */
    private fun panelTap(forward: Boolean) {
        val current = _state.value
        if (advanceJob?.isActive == true) {
            // Tapped during the pause on the whole page: go on at once, or back to the last panel.
            advanceJob?.cancel()
            if (forward) goToPage(current.pageIndex + 1) else showPanel(current.panel, animated = true)
            return
        }
        if (endOfComicHeld) {
            // The last page, whole, after its last panel: on to the end card, or back to that panel.
            if (forward) {
                showEnd()
            } else {
                endOfComicHeld = false
                showPanel(current.panel, animated = true)
            }
            return
        }
        if (wholePageHeld) {
            wholePageHeld = false
            showPanel(current.panel, animated = true)
            return
        }
        // The page's panels are still being looked for: wait for them rather than skip the page.
        if (pendingEntry != null && current.pageIndex !in panelsFound) return
        val count = panelsFound[current.pageIndex]?.panels?.size ?: 0
        when (val step = panelStep(forward, current.panel, count)) {
            is PanelStep.ToPanel -> showPanel(step.panel, animated = true)
            PanelStep.WholePageThenNext -> showWholePageThenNext()
            PanelStep.NextPage -> goOn()
            PanelStep.PreviousPage -> goToPage(current.pageIndex - 1)
        }
    }

    /** After the last panel: the whole page a moment, then the next page (the last page stays whole). */
    private fun showWholePageThenNext() {
        moveZoom(PageZoom(), animated = true)
        val index = _state.value.pageIndex
        if (index >= pageCount - 1) {
            endOfComicHeld = true
            return
        }
        advanceJob = viewModelScope.launch {
            delay(_state.value.settings.transition.moveMs + WHOLE_PAGE_PAUSE_MS)
            goToPage(index + 1)
        }
    }

    /** Panels mode double-tap: the whole page, or back to the panel if the whole page is showing. */
    private fun showWholePageOrPanel() {
        val current = _state.value
        if (current.panel < 0 || advanceJob?.isActive == true || endOfComicHeld) return
        if (wholePageHeld) {
            wholePageHeld = false
            showPanel(current.panel, animated = true)
        } else {
            wholePageHeld = true
            moveZoom(PageZoom(), animated = true)
        }
    }

    /** Zooms onto [panel] of the page shown, and saves the place. */
    private fun showPanel(panel: Int, animated: Boolean) {
        val current = _state.value
        val found = panelsFound[current.pageIndex] ?: return
        val rect = found.rects.getOrNull(panel) ?: return
        _state.update { it.copy(panel = panel) }
        if (screenWidth > 0) {
            val geometry = PageGeometry(found.pageWidth, found.pageHeight, screenWidth, screenHeight)
            moveZoom(geometry.panelZoom(rect, current.settings.margin.fraction), animated)
        }
        savePosition()
    }

    /** The page's panels are known: open it on the panel it was waiting for (or leave it whole if it has none). */
    private fun enterPanels(animated: Boolean) {
        val entry = pendingEntry ?: return
        pendingEntry = null
        val count = panelsFound[_state.value.pageIndex]?.panels?.size ?: 0
        val panel = entry.pick(count)
        if (panel >= 0) showPanel(panel, animated) else savePosition()
    }

    /**
     * Looks for the panels of page [index], then of the next page in the reading direction and
     * the one before, so turning either way doesn't wait. Stops when the page changes.
     */
    private fun lookForPanels(index: Int) {
        panelJob?.cancel()
        if (_state.value.mode != ComicReadingMode.PANELS) return
        val order = listOf(index, index + direction, index - direction).filter { it in 0 until pageCount }
        panelJob = viewModelScope.launch {
            for (page in order) {
                if (page !in panelsFound) {
                    val name = pages[page]
                    panelsFound[page] = withContext(panelDispatcher) { comicStore.panels(meta, name) { comic.read(name) } }
                }
                if (page == _state.value.pageIndex) enterPanels(animated = true)
                prepareAhead()
            }
        }
    }

    // --- pages -----------------------------------------------------------------------

    /**
     * Turns to page [index]. In Panels mode it opens on the panel [pick] chooses from the
     * panel count: by default the first going forward, the last going back.
     */
    private fun goToPage(index: Int, pick: ((Int) -> Int)? = null) {
        val current = _state.value.pageIndex
        if (index !in 0 until pageCount || index == current) return
        direction = if (index > current) 1 else -1
        val forward = direction > 0
        showPage(index, movedByReader = true, PanelEntry(saved = 0, pick ?: { count -> enteringPanel(count, forward) }))
    }

    /** A tap forward: the next page, or after the last one the end card. */
    private fun goOn() {
        val index = _state.value.pageIndex
        if (index >= pageCount - 1) showEnd() else goToPage(index + 1)
    }

    /**
     * Shows page [index] — fitted (a spread at its edge), or in Panels mode on the panel
     * [entry] picks (as soon as its panels are known) — and saves the place. Reaching the last
     * page by turning or the slider makes the comic Finished; opening it there doesn't.
     */
    private fun showPage(index: Int, movedByReader: Boolean, entry: PanelEntry) {
        sharpJob?.cancel()
        advanceJob?.cancel()
        wholePageHeld = false
        endOfComicHeld = false
        if (aheadKey?.pageIndex != index) forgetAhead()
        val panels = _state.value.mode == ComicReadingMode.PANELS
        pendingEntry = if (panels) entry else null
        val kept = pictures[index]
        val start = kept?.geometry(screenWidth, screenHeight)?.startZoom(forward = direction > 0) ?: PageZoom()
        _state.update {
            it.copy(
                pageIndex = index, picture = kept, preparing = false, failed = false,
                zoom = start, move = nextMove(animated = false), sharp = null, panel = -1, ended = false,
            )
        }
        if (panels && index in panelsFound) enterPanels(animated = false) else savePosition()
        if (movedByReader && index == pageCount - 1) DatabaseQueue.write { statusRepository.markFinished(slug) }
        if (index == pageCount - 1) findNextInFolder()
        viewModelScope.launch(decodeDispatcher) { closeRegionsExcept(setOf(index, index + 1)) }
        loadPictures(index)
        lookForPanels(index)
    }

    /** Reads the page shown (if not kept already), then its neighbour; drops every other picture. */
    private fun loadPictures(index: Int) {
        loadJob?.cancel()
        if (screenWidth == 0) return
        val keep = pagesToKeep(index, direction, pageCount)
        pictures.keys.retainAll(keep)
        loadJob = viewModelScope.launch {
            if (pictures[index] == null) {
                val slow = launch {
                    delay(PREPARING_DELAY_MS)
                    _state.update { it.copy(preparing = true) }
                }
                val picture = decodePicture(index)
                slow.cancel()
                if (picture != null) pictures[index] = picture
                showPicture(picture)
                requestSharp()
            }
            for (neighbour in keep - index) {
                if (neighbour !in pictures) decodePicture(neighbour)?.let { pictures[neighbour] = it }
            }
        }
    }

    /** The page shown got its picture (or couldn't be read). A spread still unmoved opens at its edge. */
    private fun showPicture(picture: ComicPagePicture?) {
        val current = _state.value
        val start = picture?.takeIf { current.zoom == PageZoom() }
            ?.geometry(screenWidth, screenHeight)
            ?.startZoom(forward = direction > 0)
            ?.takeIf { it != current.zoom }
        _state.update {
            it.copy(
                picture = picture, preparing = false, failed = picture == null,
                zoom = start ?: it.zoom,
                move = if (start != null) nextMove(animated = false) else it.move,
            )
        }
    }

    /** Where the page shown opens (a spread at its edge); null while its picture isn't read. */
    private fun startZoom(): PageZoom? =
        _state.value.picture?.geometry(screenWidth, screenHeight)?.startZoom(forward = direction > 0)

    /**
     * Reads one page fitted to the screen, laid out for the mode and settings. If the page
     * changes meanwhile, a picture not yet started is skipped, and one already read is thrown
     * away at once.
     */
    private suspend fun decodePicture(index: Int): ComicPagePicture? {
        val job = currentCoroutineContext().job
        val width = screenWidth
        val height = screenHeight
        val mode = _state.value.mode
        val rotate = _state.value.settings.rotateSpreads
        val fitted = withContext(decodeDispatcher + NonCancellable) {
            if (!job.isActive) return@withContext null
            decodeQuietly {
                comic.read(pages[index])?.let { bytes ->
                    ComicPageImages.decodeFitted(bytes, width, height) { w, h -> pageLayout(w, h, mode, rotate) }
                }
            }
        }
        if (!job.isActive) {
            fitted?.bitmap?.recycle()
            throw CancellationException()
        }
        return fitted?.let { ComicPagePicture(index, it.bitmap.asImageBitmap(), it.pageWidth, it.pageHeight, it.layout) }
    }

    // --- the end ---------------------------------------------------------------------

    /** Past the last page: the comic is Finished, and the end card offers the next thing in the folder. */
    private fun showEnd() {
        advanceJob?.cancel()
        DatabaseQueue.write { statusRepository.markFinished(slug) }
        _state.update { it.copy(ended = true, overlayShown = false) }
        findNextInFolder()
    }

    /** Looks (once) for the next comic or note in this comic's folder, in the order the folder shows. */
    private fun findNextInFolder() {
        if (nextJob != null) return
        nextJob = viewModelScope.launch {
            val next = withContext(Dispatchers.IO) { nextInFolder() }
            _state.update { it.copy(next = next, nextKnown = true) }
        }
    }

    /** On Dispatchers.IO: the first note, or comic that can be opened, after this one. A comic not read yet is read now. */
    private fun nextInFolder(): NextInFolder? {
        val items = comicStore.list(parentPath(meta.path))
        for (item in itemsAfter(items, meta.path)) {
            when (item.kind) {
                ComicItemKind.NOTE -> return NextInFolder.Note(item.path, item.title)
                ComicItemKind.COMIC ->
                    comicStore.prepared(item.path)?.takeIf { it.canOpen }?.let { return NextInFolder.Comic(it, item.title) }
                ComicItemKind.FOLDER -> Unit
            }
        }
        return null
    }

    // --- zoom ------------------------------------------------------------------------

    private fun geometry(): PageGeometry? {
        val picture = _state.value.picture ?: return null
        if (screenWidth == 0) return null
        return picture.geometry(screenWidth, screenHeight)
    }

    /** A new move: [animated] ones slide at the Panel transition setting's speed (Off jumps). */
    private fun nextMove(animated: Boolean): ZoomMove =
        ZoomMove(++moveCount, if (animated) _state.value.settings.transition.moveMs else 0)

    /** Pinch, drag or Full page double-tap: the page follows at once. */
    private fun setZoom(zoom: PageZoom) {
        if (zoom == _state.value.zoom) return
        _state.update { it.copy(zoom = zoom, move = nextMove(animated = false), sharp = if (zoom.isZoomed) it.sharp else null) }
        requestSharp()
    }

    /**
     * A move the viewer makes itself (to a panel, or out to the whole page). The sharp piece
     * already read stays, drawn where it belongs, until a sharper one for the new spot is read.
     */
    private fun moveZoom(zoom: PageZoom, animated: Boolean) {
        _state.update { it.copy(zoom = zoom, move = nextMove(animated)) }
        requestSharp()
    }

    /**
     * Once the fingers rest a moment, reads the part of the page on screen at full detail
     * (BitmapRegionDecoder). Until then the fitted picture is drawn enlarged. A piece read
     * ahead for this spot is used at once. Then the next spot is read ahead.
     */
    private fun requestSharp() {
        sharpJob?.cancel()
        val current = _state.value
        val picture = current.picture
        if (picture == null || screenWidth == 0 || !current.zoom.isZoomed) {
            prepareAhead()
            return
        }
        val geometry = picture.geometry(screenWidth, screenHeight)
        val region = geometry.visibleRegion(current.zoom)
        if (region.isEmpty) return
        val sample = geometry.regionSampleSize(current.zoom)
        if (current.sharp?.fits(picture, region, sample) == true) {
            prepareAhead()
            return
        }
        val ahead = aheadSharp
        if (ahead != null && ahead.fits(picture, region, sample)) {
            aheadSharp = null
            aheadKey = null
            _state.update { it.copy(sharp = ahead) }
            prepareAhead()
            return
        }
        sharpJob = viewModelScope.launch {
            delay(SHARP_DELAY_MS)
            val piece = readSharp(picture.pageIndex, picture.layout, picture.pageWidth, region, sample)
            if (piece != null) _state.update { it.copy(sharp = piece) }
            // This spot is done, so the next one can be read ahead.
            sharpJob = null
            prepareAhead()
        }
    }

    /**
     * Panels mode: reads the sharp picture of where the next tap forward lands (the next
     * panel, or the next page's first), so the move ends sharp. Waits for the spot shown to
     * be sharp first; one piece ahead at a time.
     */
    private fun prepareAhead() {
        val current = _state.value
        if (current.mode != ComicReadingMode.PANELS || screenWidth == 0 || sharpJob?.isActive == true) return
        val index = current.pageIndex
        val count = panelsFound[index]?.panels?.size ?: return
        val nextCount = if (index + 1 < pageCount) panelsFound[index + 1]?.panels?.size else null
        val spot = nextPanelSpot(index, current.panel, count, nextCount) ?: return
        val found = panelsFound[spot.page] ?: return
        val rect = found.rects.getOrNull(spot.panel) ?: return
        val geometry = PageGeometry(found.pageWidth, found.pageHeight, screenWidth, screenHeight)
        val zoom = geometry.panelZoom(rect, current.settings.margin.fraction)
        if (!zoom.isZoomed) return
        val region = geometry.visibleRegion(zoom)
        if (region.isEmpty) return
        val key = AheadKey(spot.page, region, geometry.regionSampleSize(zoom))
        if (key == aheadKey) return
        forgetAhead()
        aheadKey = key
        aheadJob = viewModelScope.launch {
            aheadSharp = readSharp(key.pageIndex, PageLayout.WHOLE, found.pageWidth, key.region, key.sampleSize)
        }
    }

    private fun forgetAhead() {
        aheadJob?.cancel()
        aheadJob = null
        aheadSharp = null
        aheadKey = null
    }

    /** Reads one sharp piece on decodeDispatcher; a piece no longer wanted when it's read is thrown away. */
    private suspend fun readSharp(index: Int, layout: PageLayout, pageWidth: Int, region: PixelRect, sample: Int): SharpArea? {
        val job = currentCoroutineContext().job
        val bitmap = withContext(decodeDispatcher + NonCancellable) {
            if (job.isActive) decodeQuietly { decodeRegion(index, layout, pageWidth, region, sample) } else null
        }
        if (!job.isActive) {
            bitmap?.recycle()
            throw CancellationException()
        }
        return bitmap?.let { SharpArea(index, layout, region, sample, it.asImageBitmap()) }
    }

    /** On decodeDispatcher: each page is opened for pieces once, then reused while it's kept. A turned page's piece is turned too. */
    private fun decodeRegion(index: Int, layout: PageLayout, pageWidth: Int, region: PixelRect, sample: Int): Bitmap? {
        if (index !in regionDecoders) regionDecoders[index] = comic.read(pages[index])?.let(ComicPageImages::openRegions)
        val decoder = regionDecoders[index] ?: return null
        val turned = layout == PageLayout.ROTATED
        val inPage = if (turned) rotatedRegionInPage(region, pageWidth) else region
        val bitmap = ComicPageImages.decodeRegion(decoder, inPage, sample) ?: return null
        return if (turned) ComicPageImages.turnLeft(bitmap) else bitmap
    }

    /** On decodeDispatcher: closes the pages opened for pieces, except [keep]. */
    private fun closeRegionsExcept(keep: Set<Int>) {
        for (page in regionDecoders.keys - keep) regionDecoders.remove(page)?.recycle()
    }

    // --- saving ----------------------------------------------------------------------

    /** The panel saved with the page: 0 in Full page mode or for a page shown whole. */
    private fun panelToSave(): Int {
        val current = _state.value
        return when {
            current.mode != ComicReadingMode.PANELS -> 0
            else -> pendingEntry?.saved ?: savedPanelNumber(current.panel)
        }
    }

    private fun savePosition() {
        val page = _state.value.pageIndex + 1
        val panel = panelToSave()
        DatabaseQueue.write { positionRepository.save(slug, page, panel) }
    }

    /** Waits for the place to be saved, so it's never lost even if the app is closed right away. */
    private fun flushPosition() {
        val index = _state.value.pageIndex
        if (index < 0) return
        val panel = panelToSave()
        DatabaseQueue.writeNow { positionRepository.save(slug, index + 1, panel) }
    }

    override fun onScreenHide(screen: SimpleLightScreen<NextInFolder>) {
        super.onScreenHide(screen)
        flushPosition()
    }

    override fun onAppPause() {
        super.onAppPause()
        flushPosition()
    }

    /** The screen is gone: the CBZ and the pages opened for pieces are closed, after any read in progress. */
    override fun onCleared() {
        super.onCleared()
        CoroutineScope(decodeDispatcher).launch {
            closeRegionsExcept(emptySet())
            comic.close()
        }
    }

    companion object {
        /** A page not ready after this long shows "Preparing…" (quicker ones just appear). */
        private const val PREPARING_DELAY_MS = 300L

        /** How long the fingers rest before the zoomed part is read sharply. */
        private const val SHARP_DELAY_MS = 150L

        /** How long the finger rests on the slider before the page it points at is opened. */
        private const val SLIDER_REST_MS = 200L
    }
}

/** True when this piece is of [picture]'s page as laid out now, holds all of [region], and is at least as sharp as [sample] needs. */
private fun SharpArea.fits(picture: ComicPagePicture, region: PixelRect, sample: Int): Boolean =
    pageIndex == picture.pageIndex && layout == picture.layout && sampleSize <= sample && this.region.covers(region)

/** True when this rectangle holds all of [other]. */
private fun PixelRect.covers(other: PixelRect): Boolean =
    left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

/** A picture that can't be read, or is too big for the memory, is just not shown. */
private inline fun <T> decodeQuietly(read: () -> T?): T? =
    try {
        read()
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
