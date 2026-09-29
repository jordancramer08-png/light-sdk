package com.thelightphone.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.thelightphone.reader.comics.PageGeometry
import com.thelightphone.reader.comics.PageZoom
import com.thelightphone.reader.comics.comicSlug
import com.thelightphone.reader.comics.sliderFraction
import com.thelightphone.reader.data.ComicMeta
import com.thelightphone.reader.data.ComicPositionRepository
import com.thelightphone.reader.data.ComicStore
import com.thelightphone.reader.data.ReadingStatusRepository
import com.thelightphone.reader.data.readerDatabase
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.LightTouchableProgressBar
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlin.math.roundToInt

/** Messages on the black page area are this gray in every theme. */
private val PAGE_MESSAGE_COLOR = Color(0xFF9E9E9E)

/**
 * The comic viewer (CLAUDE.md 12): one page at a time, fitted to a black screen, no bars.
 * Tap right for the next page, left for the previous, the top for the overlay (back, title,
 * page number, slider, ADD TO LIST, DETAILS). Pinch or double-tap to zoom, drag to move.
 */
class ComicScreen(
    sealedActivity: SealedLightActivity,
    private val meta: ComicMeta,
    private val title: String,
) : LightScreen<Unit, ComicViewModel>(sealedActivity) {

    private val slug = comicSlug(meta.path)

    override val viewModelClass: Class<ComicViewModel>
        get() = ComicViewModel::class.java

    override fun createViewModel() = ComicViewModel(
        meta,
        ComicStore(lightContext.filesDir).comicFile(meta.path),
        ComicPositionRepository.getInstance { lightContext.readerDatabase() },
        ReadingStatusRepository.getInstance { lightContext.readerDatabase() },
    )

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val theme by ReaderThemeController.theme.collectAsState()

        ThemedWith(theme) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                ComicPage(state, viewModel, Modifier.fillMaxSize())
                PageMessage(state)
                if (state.overlayShown) Overlay(state)
            }
        }
    }

    @Composable
    private fun BoxScope.Overlay(state: ComicViewState) {
        val colors = LightThemeTokens.colors
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(colors.background)
                // Taps on the overlay stay on it instead of reaching the page.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(title),
            )
            LightText(
                text = "Page ${state.pageIndex + 1} of ${viewModel.pageCount}",
                variant = LightTextVariant.Detail,
                align = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 0.5f.gridUnitsAsDp()),
            )
            if (viewModel.pageCount > 1) {
                LightTouchableProgressBar(
                    colors = colors,
                    progress = sliderFraction(state.pageIndex, viewModel.pageCount),
                    onValueChange = viewModel::jumpToFraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 1.5f.gridUnitsAsDp()),
                )
            }
            HairlineDivider()
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(colors.background)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            LightBottomBar(
                items = listOf(
                    LightBarButton.Text(text = "ADD TO LIST", onClick = ::openAddToList),
                    LightBarButton.Text(text = "DETAILS", onClick = ::openDetails),
                ),
            )
        }
    }

    private fun openAddToList() {
        viewModel.hideOverlay()
        navigateTo(screenFactory = { AddToListScreen(it, slug) })
    }

    private fun openDetails() {
        viewModel.hideOverlay()
        navigateTo(screenFactory = { ComicDetailsScreen(it, meta, title) })
    }
}

/**
 * The page, drawn at its zoom: the fitted picture, and over it the sharp picture of the
 * zoomed-in part once it's read. Taps, double-taps, pinches and drags go to [viewModel].
 */
@Composable
private fun ComicPage(state: ComicViewState, viewModel: ComicViewModel, modifier: Modifier) {
    Canvas(
        modifier = modifier
            .onSizeChanged { viewModel.setScreenSize(it.width, it.height) }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    viewModel.transform(centroid.x, centroid.y, pan.x, pan.y, zoom)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { viewModel.doubleTap(it.x, it.y) },
                    onTap = { viewModel.tap(it.x, it.y) },
                )
            },
    ) {
        val picture = state.picture ?: return@Canvas
        val geometry = PageGeometry(picture.imageWidth, picture.imageHeight, size.width.toInt(), size.height.toInt())
        drawPicture(picture, geometry, state.zoom)
        val sharp = state.sharp?.takeIf { it.pageIndex == picture.pageIndex } ?: return@Canvas
        drawSharpArea(sharp, geometry, state.zoom)
    }
}

private fun DrawScope.drawPicture(picture: ComicPagePicture, geometry: PageGeometry, zoom: PageZoom) {
    drawImage(
        image = picture.image,
        dstOffset = IntOffset(geometry.pageLeft(zoom).roundToInt(), geometry.pageTop(zoom).roundToInt()),
        dstSize = IntSize(geometry.shownWidth(zoom).roundToInt(), geometry.shownHeight(zoom).roundToInt()),
        filterQuality = FilterQuality.Medium,
    )
}

private fun DrawScope.drawSharpArea(sharp: SharpArea, geometry: PageGeometry, zoom: PageZoom) {
    val scale = geometry.scale(zoom)
    val left = geometry.pageLeft(zoom) + sharp.region.left * scale
    val top = geometry.pageTop(zoom) + sharp.region.top * scale
    drawImage(
        image = sharp.image,
        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
        dstSize = IntSize((sharp.region.width * scale).roundToInt(), (sharp.region.height * scale).roundToInt()),
        filterQuality = FilterQuality.Medium,
    )
}

/** "Preparing…" when a page is slow to read, or a note when it can't be read. */
@Composable
private fun BoxScope.PageMessage(state: ComicViewState) {
    val message = when {
        state.picture != null -> return
        state.failed -> "Can't show this page."
        state.preparing -> "Preparing…"
        else -> return
    }
    LightText(
        text = message,
        variant = LightTextVariant.Copy,
        align = TextAlign.Center,
        color = PAGE_MESSAGE_COLOR,
        modifier = Modifier.align(Alignment.Center),
    )
}
