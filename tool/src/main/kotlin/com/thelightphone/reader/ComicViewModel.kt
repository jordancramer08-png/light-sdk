package com.thelightphone.reader

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewModelScope
import com.thelightphone.reader.comics.PageGeometry
import com.thelightphone.reader.comics.PageTap
import com.thelightphone.reader.comics.PageZoom
import com.thelightphone.reader.comics.PixelRect
import com.thelightphone.reader.comics.comicSlug
import com.thelightphone.reader.comics.openingPageIndex
import com.thelightphone.reader.comics.pageTap
import com.thelightphone.reader.comics.pagesToKeep
import com.thelightphone.reader.comics.sliderPage
import com.thelightphone.reader.data.ComicMeta
import com.thelightphone.reader.data.ComicPageImages
import com.thelightphone.reader.data.ComicPositionRepository
import com.thelightphone.reader.data.DatabaseQueue
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
import java.io.File

/** A page's picture fitted to the screen, ready to draw. [imageWidth] × [imageHeight] is the page's full size. */
class ComicPagePicture(val pageIndex: Int, val image: ImageBitmap, val imageWidth: Int, val imageHeight: Int)

/** A sharp picture of the zoomed-in part of a page: [region] in the page's own pixels. */
class SharpArea(val pageIndex: Int, val region: PixelRect, val sampleSize: Int, val image: ImageBitmap)

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
    val sharp: SharpArea? = null,
    val overlayShown: Boolean = false,
) {
    val isReady: Boolean get() = pageIndex >= 0
}

/**
 * The comic viewer (CLAUDE.md 12). Pages are read from the CBZ one at a time off the main
 * thread. In memory: the page shown and the next one in the reading direction, each fitted
 * to the screen, and while zoomed one sharp picture of the part on screen. The place is
 * saved on every page turn and saved before leaving (waiting for it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComicViewModel(
    meta: ComicMeta,
    private val comicFile: File,
    private val positionRepository: ComicPositionRepository,
    private val statusRepository: ReadingStatusRepository,
) : LightViewModel<Unit>() {

    private val slug = comicSlug(meta.path)
    private val pages = meta.pages
    val pageCount: Int = pages.size

    private val _state = MutableStateFlow(ComicViewState())
    val state: StateFlow<ComicViewState> = _state.asStateFlow()

    /** Pages are read one at a time, so two big pictures are never being decoded at once. */
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Fitted pictures kept: the page shown and its neighbour (main thread only). */
    private val pictures = mutableMapOf<Int, ComicPagePicture>()

    /** +1 after going forward, -1 after going back: which neighbour to read ahead. */
    private var direction = 1
    private var screenWidth = 0
    private var screenHeight = 0
    private var loadJob: Job? = null
    private var sharpJob: Job? = null

    // Only touched on decodeDispatcher: the page open for sharp zoomed pieces.
    private var regionPage = -1
    private var regionDecoder: BitmapRegionDecoder? = null

    init {
        // Opening a comic makes it Reading (unless it's already Reading or Finished).
        DatabaseQueue.write { statusRepository.markOpened(slug) }
        viewModelScope.launch {
            val saved = DatabaseQueue.read { positionRepository.get(slug) }
            showPage(openingPageIndex(saved?.page, pageCount), movedByReader = false)
        }
    }

    // --- from the screen -------------------------------------------------------------

    /** The page area's size in pixels; pictures are read to fit it. */
    fun setScreenSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || (width == screenWidth && height == screenHeight)) return
        screenWidth = width
        screenHeight = height
        pictures.clear()
        val index = _state.value.pageIndex
        if (index < 0) return
        _state.update { it.copy(picture = null, zoom = PageZoom(), sharp = null) }
        loadPictures(index)
    }

    fun tap(x: Float, y: Float) {
        val current = _state.value
        if (!current.isReady || screenWidth == 0) return
        if (current.overlayShown) {
            hideOverlay()
            return
        }
        when (pageTap(x, y, screenWidth, screenHeight, current.zoom.isZoomed)) {
            PageTap.TOGGLE_OVERLAY -> _state.update { it.copy(overlayShown = true) }
            PageTap.PREVIOUS -> goToPage(current.pageIndex - 1)
            PageTap.NEXT -> goToPage(current.pageIndex + 1)
            PageTap.NOTHING -> Unit
        }
    }

    fun hideOverlay() {
        _state.update { it.copy(overlayShown = false) }
    }

    fun doubleTap(x: Float, y: Float) {
        val geometry = geometry() ?: return
        setZoom(geometry.doubleTap(_state.value.zoom, x, y))
    }

    /** A pinch or drag: zoom by [factor] around ([focusX], [focusY]) and move by ([dx], [dy]). */
    fun transform(focusX: Float, focusY: Float, dx: Float, dy: Float, factor: Float) {
        val geometry = geometry() ?: return
        val zoomed = geometry.zoomBy(_state.value.zoom, factor, focusX, focusY)
        setZoom(geometry.panBy(zoomed, dx, dy))
    }

    /** From the overlay's slider: [fraction] 0 is the first page, 1 the last. */
    fun jumpToFraction(fraction: Float) {
        goToPage(sliderPage(fraction, pageCount))
    }

    // --- pages -----------------------------------------------------------------------

    private fun goToPage(index: Int) {
        val current = _state.value.pageIndex
        if (index !in 0 until pageCount || index == current) return
        direction = if (index > current) 1 else -1
        showPage(index, movedByReader = true)
    }

    /**
     * Shows page [index] fitted, and saves the place. Reaching the last page by turning or
     * the slider makes the comic Finished; opening it there doesn't.
     */
    private fun showPage(index: Int, movedByReader: Boolean) {
        sharpJob?.cancel()
        _state.update {
            it.copy(pageIndex = index, picture = pictures[index], preparing = false, failed = false, zoom = PageZoom(), sharp = null)
        }
        DatabaseQueue.write { positionRepository.save(slug, index + 1, 0) }
        if (movedByReader && index == pageCount - 1) DatabaseQueue.write { statusRepository.markFinished(slug) }
        viewModelScope.launch(decodeDispatcher) { if (regionPage != index) closeRegions() }
        loadPictures(index)
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

    private fun setZoom(zoom: PageZoom) {
        if (zoom == _state.value.zoom) return
        _state.update { it.copy(zoom = zoom, sharp = if (zoom.isZoomed) it.sharp else null) }
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

    /** Waits for the place to be saved, so it's never lost even if the app is closed right away. */
    private fun flushPosition() {
        val index = _state.value.pageIndex
        if (index < 0) return
        DatabaseQueue.writeNow { positionRepository.save(slug, index + 1, 0) }
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
