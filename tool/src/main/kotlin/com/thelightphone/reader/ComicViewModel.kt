package com.thelightphone.reader

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewModelScope
import com.thelightphone.reader.comics.ComicReadingMode
import com.thelightphone.reader.comics.PANEL_MOVE_MS
import com.thelightphone.reader.comics.PageGeometry
import com.thelightphone.reader.comics.PageTap
import com.thelightphone.reader.comics.PageZoom
import com.thelightphone.reader.comics.PanelStep
import com.thelightphone.reader.comics.PixelRect
import com.thelightphone.reader.comics.WHOLE_PAGE_PAUSE_MS
import com.thelightphone.reader.comics.comicSlug
import com.thelightphone.reader.comics.enteringPanel
import com.thelightphone.reader.comics.openingPageIndex
import com.thelightphone.reader.comics.openingPanelIndex
import com.thelightphone.reader.comics.pageTap
import com.thelightphone.reader.comics.pagesToKeep
import com.thelightphone.reader.comics.panelStep
import com.thelightphone.reader.comics.panelZoom
import com.thelightphone.reader.comics.savedPanelNumber
import com.thelightphone.reader.comics.sliderPage
import com.thelightphone.reader.data.ComicMeta
import com.thelightphone.reader.data.ComicPageImages
import com.thelightphone.reader.data.ComicPositionRepository
import com.thelightphone.reader.data.ComicReadingModePreference
import com.thelightphone.reader.data.ComicStore
import com.thelightphone.reader.data.DatabaseQueue
import com.thelightphone.reader.data.PagePanels
import com.thelightphone.reader.data.ReadingStatusRepository
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.CancellationException
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

/** A page's picture fitted to the screen, ready to draw. [imageWidth] × [imageHeight] is the page's full size. */
class ComicPagePicture(val pageIndex: Int, val image: ImageBitmap, val imageWidth: Int, val imageHeight: Int)

/** A sharp picture of the zoomed-in part of a page: [region] in the page's own pixels. */
class SharpArea(val pageIndex: Int, val region: PixelRect, val sampleSize: Int, val image: ImageBitmap)

/**
 * One change of zoom, for the screen: [animated] ones slide there over [PANEL_MOVE_MS],
 * the others jump. Each has a new [id], so the screen knows a new one started.
 */
data class ZoomMove(val id: Int, val animated: Boolean)

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
    val move: ZoomMove = ZoomMove(0, animated = false),
    val sharp: SharpArea? = null,
    val overlayShown: Boolean = false,
    val mode: ComicReadingMode = ComicReadingMode.DEFAULT,
    /** In Panels mode, the panel shown (0-based); -1 while the page is shown whole. */
    val panel: Int = -1,
) {
    val isReady: Boolean get() = pageIndex >= 0
}

/**
 * The panel a page opens on once its panels are known: [pick] turns the panel count into the
 * panel. Until then [saved] (1-based, 0 = whole page) is what's saved as the place.
 */
private class PanelEntry(val saved: Int, val pick: (Int) -> Int)

/**
 * The comic viewer (CLAUDE.md 12). Pages are read from the CBZ one at a time off the main
 * thread. In memory: the page shown and the next one in the reading direction, each fitted
 * to the screen, and while zoomed one sharp picture of the part on screen. In Panels mode a
 * page's panels are looked for off the main thread too, the next page's ahead of time, and
 * each tap moves the zoom to the next panel. The place (page and panel) is saved on every
 * move and saved before leaving (waiting for it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComicViewModel(
    private val meta: ComicMeta,
    private val comicStore: ComicStore,
    private val positionRepository: ComicPositionRepository,
    private val statusRepository: ReadingStatusRepository,
    private val modePreference: ComicReadingModePreference,
) : LightViewModel<Unit>() {

    private val slug = comicSlug(meta.path)
    private val comicFile = comicStore.comicFile(meta.path)
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

    /** In Panels mode, the whole page is shown until the next tap (double-tap, or the end of the comic). */
    private var wholePageHeld = false

    /** +1 after going forward, -1 after going back: which neighbour to read ahead. */
    private var direction = 1
    private var screenWidth = 0
    private var screenHeight = 0
    private var moveCount = 0
    private var loadJob: Job? = null
    private var sharpJob: Job? = null
    private var panelJob: Job? = null

    /** The pause on the whole page after its last panel, before the next page. */
    private var advanceJob: Job? = null

    // Only touched on decodeDispatcher: the page open for sharp zoomed pieces.
    private var regionPage = -1
    private var regionDecoder: BitmapRegionDecoder? = null

    init {
        // Opening a comic makes it Reading (unless it's already Reading or Finished).
        DatabaseQueue.write { statusRepository.markOpened(slug) }
        viewModelScope.launch {
            val mode = modePreference.load()
            val saved = DatabaseQueue.read { positionRepository.get(slug) }
            _state.update { it.copy(mode = mode) }
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
        val current = _state.value
        if (current.pageIndex < 0) return
        _state.update { it.copy(picture = null, zoom = PageZoom(), move = nextMove(animated = false), sharp = null) }
        if (current.panel >= 0 && !wholePageHeld) showPanel(current.panel, animated = false)
        loadPictures(current.pageIndex)
    }

    fun tap(x: Float, y: Float) {
        val current = _state.value
        if (!current.isReady || screenWidth == 0) return
        if (current.overlayShown) {
            hideOverlay()
            return
        }
        val panels = current.mode == ComicReadingMode.PANELS
        // In Panels mode the page is always zoomed onto a panel; taps still move on.
        when (pageTap(x, y, screenWidth, screenHeight, zoomed = !panels && current.zoom.isZoomed)) {
            PageTap.TOGGLE_OVERLAY -> _state.update { it.copy(overlayShown = true) }
            PageTap.PREVIOUS -> if (panels) panelTap(forward = false) else goToPage(current.pageIndex - 1)
            PageTap.NEXT -> if (panels) panelTap(forward = true) else goToPage(current.pageIndex + 1)
            PageTap.NOTHING -> Unit
        }
    }

    fun hideOverlay() {
        _state.update { it.copy(overlayShown = false) }
    }

    /** Full page mode: zoom in at the spot, or fit again. Panels mode: the whole page until the next tap. */
    fun doubleTap(x: Float, y: Float) {
        if (_state.value.mode == ComicReadingMode.PANELS) {
            showWholePageOrPanel()
            return
        }
        val geometry = geometry() ?: return
        setZoom(geometry.doubleTap(_state.value.zoom, x, y))
    }

    /** A pinch or drag: zoom by [factor] around ([focusX], [focusY]) and move by ([dx], [dy]). Full page mode only. */
    fun transform(focusX: Float, focusY: Float, dx: Float, dy: Float, factor: Float) {
        if (_state.value.mode == ComicReadingMode.PANELS) return
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
        if (mode == current.mode || !current.isReady) return
        advanceJob?.cancel()
        wholePageHeld = false
        _state.update { it.copy(mode = mode) }
        viewModelScope.launch { modePreference.save(mode) }
        if (mode == ComicReadingMode.FULL_PAGE) {
            pendingEntry = null
            panelJob?.cancel()
            _state.update { it.copy(panel = -1) }
            moveZoom(PageZoom(), animated = true)
            savePosition()
        } else {
            pendingEntry = PanelEntry(saved = 0) { count -> enteringPanel(count, forward = true) }
            savePosition()
            if (current.pageIndex in panelsFound) enterPanels(animated = true) else lookForPanels(current.pageIndex)
        }
    }

    /** From the overlay's slider: [fraction] 0 is the first page, 1 the last. Opens the page on its first panel. */
    fun jumpToFraction(fraction: Float) {
        goToPage(sliderPage(fraction, pageCount)) { count -> enteringPanel(count, forward = true) }
    }

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
            PanelStep.NextPage -> goToPage(current.pageIndex + 1)
            PanelStep.PreviousPage -> goToPage(current.pageIndex - 1)
        }
    }

    /** After the last panel: the whole page a moment, then the next page (the last page just stays whole). */
    private fun showWholePageThenNext() {
        moveZoom(PageZoom(), animated = true)
        val index = _state.value.pageIndex
        if (index >= pageCount - 1) {
            wholePageHeld = true
            return
        }
        advanceJob = viewModelScope.launch {
            delay(PANEL_MOVE_MS + WHOLE_PAGE_PAUSE_MS)
            goToPage(index + 1)
        }
    }

    /** Panels mode double-tap: the whole page, or back to the panel if the whole page is showing. */
    private fun showWholePageOrPanel() {
        val current = _state.value
        if (current.panel < 0 || advanceJob?.isActive == true) return
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
        val index = _state.value.pageIndex
        val found = panelsFound[index] ?: return
        val rect = found.rects.getOrNull(panel) ?: return
        _state.update { it.copy(panel = panel) }
        if (screenWidth > 0) {
            val geometry = PageGeometry(found.pageWidth, found.pageHeight, screenWidth, screenHeight)
            moveZoom(geometry.panelZoom(rect), animated)
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
                    panelsFound[page] = withContext(panelDispatcher) { comicStore.panels(meta, pages[page]) }
                }
                if (page == _state.value.pageIndex) enterPanels(animated = true)
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

    /**
     * Shows page [index] — fitted, or in Panels mode on the panel [entry] picks (as soon as
     * its panels are known) — and saves the place. Reaching the last page by turning or the
     * slider makes the comic Finished; opening it there doesn't.
     */
    private fun showPage(index: Int, movedByReader: Boolean, entry: PanelEntry) {
        sharpJob?.cancel()
        advanceJob?.cancel()
        wholePageHeld = false
        val panels = _state.value.mode == ComicReadingMode.PANELS
        pendingEntry = if (panels) entry else null
        _state.update {
            it.copy(
                pageIndex = index, picture = pictures[index], preparing = false, failed = false,
                zoom = PageZoom(), move = nextMove(animated = false), sharp = null, panel = -1,
            )
        }
        if (panels && index in panelsFound) enterPanels(animated = false) else savePosition()
        if (movedByReader && index == pageCount - 1) DatabaseQueue.write { statusRepository.markFinished(slug) }
        viewModelScope.launch(decodeDispatcher) { if (regionPage != index) closeRegions() }
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
                _state.update { it.copy(picture = picture, preparing = false, failed = picture == null) }
                requestSharp()
            }
            for (neighbour in keep - index) {
                if (neighbour !in pictures) decodePicture(neighbour)?.let { pictures[neighbour] = it }
            }
        }
    }

    /**
     * Reads one page fitted to the screen. If the page changes meanwhile, a picture not yet
     * started is skipped, and one already read is thrown away at once.
     */
    private suspend fun decodePicture(index: Int): ComicPagePicture? {
        val job = currentCoroutineContext().job
        val width = screenWidth
        val height = screenHeight
        val fitted = withContext(decodeDispatcher + NonCancellable) {
            if (job.isActive) decodeQuietly { ComicPageImages.readPage(comicFile, pages[index])?.let { ComicPageImages.decodeFitted(it, width, height) } } else null
        }
        if (!job.isActive) {
            fitted?.bitmap?.recycle()
            throw CancellationException()
        }
        return fitted?.let { ComicPagePicture(index, it.bitmap.asImageBitmap(), it.imageWidth, it.imageHeight) }
    }

    // --- zoom ------------------------------------------------------------------------

    private fun geometry(): PageGeometry? {
        val picture = _state.value.picture ?: return null
        if (screenWidth == 0) return null
        return PageGeometry(picture.imageWidth, picture.imageHeight, screenWidth, screenHeight)
    }

    private fun nextMove(animated: Boolean): ZoomMove = ZoomMove(++moveCount, animated)

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
     * (BitmapRegionDecoder). Until then the fitted picture is drawn enlarged.
     */
    private fun requestSharp() {
        sharpJob?.cancel()
        val current = _state.value
        val geometry = geometry() ?: return
        if (!current.zoom.isZoomed) return
        val region = geometry.visibleRegion(current.zoom)
        if (region.isEmpty) return
        val sample = geometry.regionSampleSize(current.zoom)
        val shown = current.sharp
        if (shown != null && shown.pageIndex == current.pageIndex && shown.sampleSize <= sample && shown.region.covers(region)) return
        val index = current.pageIndex
        sharpJob = viewModelScope.launch {
            delay(SHARP_DELAY_MS)
            val job = currentCoroutineContext().job
            val bitmap = withContext(decodeDispatcher + NonCancellable) {
                if (job.isActive) decodeQuietly { decodeRegion(index, region, sample) } else null
            }
            if (!job.isActive) {
                bitmap?.recycle()
                return@launch
            }
            if (bitmap != null) _state.update { it.copy(sharp = SharpArea(index, region, sample, bitmap.asImageBitmap())) }
        }
    }

    /** On decodeDispatcher: the page opened for pieces once, then reused while it's shown. */
    private fun decodeRegion(index: Int, region: PixelRect, sample: Int): Bitmap? {
        if (regionPage != index) {
            closeRegions()
            regionPage = index
            regionDecoder = ComicPageImages.readPage(comicFile, pages[index])?.let(ComicPageImages::openRegions)
        }
        val decoder = regionDecoder ?: return null
        return ComicPageImages.decodeRegion(decoder, region, sample)
    }

    private fun closeRegions() {
        regionDecoder?.recycle()
        regionDecoder = null
        regionPage = -1
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

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        flushPosition()
    }

    override fun onAppPause() {
        super.onAppPause()
        flushPosition()
    }

    companion object {
        /** A page not ready after this long shows "Preparing…" (quicker ones just appear). */
        private const val PREPARING_DELAY_MS = 300L

        /** How long the fingers rest before the zoomed part is read sharply. */
        private const val SHARP_DELAY_MS = 150L
    }
}

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
