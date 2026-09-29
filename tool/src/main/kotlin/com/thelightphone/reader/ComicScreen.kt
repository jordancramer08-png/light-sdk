package com.thelightphone.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.thelightphone.reader.comics.ComicReadingMode
import com.thelightphone.reader.comics.PageGeometry
import com.thelightphone.reader.comics.PageLayout
import com.thelightphone.reader.comics.PageZoom
import com.thelightphone.reader.comics.PixelRect
import com.thelightphone.reader.comics.between
import com.thelightphone.reader.comics.cleanUpMatrix
import com.thelightphone.reader.comics.comicSlug
import com.thelightphone.reader.comics.sliderFraction
import com.thelightphone.reader.data.ComicCleanUpPreference
import com.thelightphone.reader.data.ComicMeta
import com.thelightphone.reader.data.ComicPositionRepository
import com.thelightphone.reader.data.ComicReadingModePreference
import com.thelightphone.reader.data.ComicStore
import com.thelightphone.reader.data.ComicViewSettingsPreference
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
import com.thelightphone.sdk.ui.lightClickable
import kotlin.math.roundToInt

/** Messages on the black page area are this gray in every theme. */
private val PAGE_MESSAGE_COLOR = Color(0xFF9E9E9E)

/**
 * The comic viewer (CLAUDE.md 12): one page at a time on a black screen, no bars. Tap right
 * to go on, left to go back, the top for the overlay (back, title, page number, slider,
 * FULL PAGE / PANELS, the mode's settings, Crop margins, Clean up scans, ADD TO LIST, DETAILS); a long press switches
 * Full page ↔ Panels. Full page: the page fitted (a spread as tall as the screen, or turned),
 * pinch or double-tap to zoom, drag to move. Panels: zoomed onto one panel at a time;
 * double-tap shows the whole page until the next tap. Going on past the last page shows the
 * end card; its NEXT IN FOLDER hands the next comic or note back to the opener to open.
 */
class ComicScreen(
    sealedActivity: SealedLightActivity,
    private val meta: ComicMeta,
    private val title: String,
) : LightScreen<NextInFolder, ComicViewModel>(sealedActivity) {

    private val slug = comicSlug(meta.path)

    override val viewModelClass: Class<ComicViewModel>
        get() = ComicViewModel::class.java

    override fun createViewModel() = ComicViewModel(
        meta,
        ComicStore(lightContext.filesDir),
        ComicPositionRepository.getInstance { lightContext.readerDatabase() },
        ReadingStatusRepository.getInstance { lightContext.readerDatabase() },
        ComicReadingModePreference(lightContext.dataStore),
        ComicViewSettingsPreference(lightContext.dataStore),
        ComicCleanUpPreference(lightContext.dataStore),
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
                if (state.ended) EndCard(state)
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
                text = "Page ${state.shownPageNumber + 1} of ${viewModel.pageCount}",
                variant = LightTextVariant.Detail,
                align = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 0.5f.gridUnitsAsDp()),
            )
            if (viewModel.pageCount > 1) {
                LightTouchableProgressBar(
                    colors = colors,
                    progress = sliderFraction(state.shownPageNumber, viewModel.pageCount),
                    onValueChange = viewModel::jumpToFraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 1.5f.gridUnitsAsDp()),
                )
            }
            ModeBar(current = state.mode, onSelect = viewModel::setMode)
            HairlineDivider()
            ModeSettings(state)
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

    /**
     * The settings of the mode in use (panel margin and transition in Panels, Rotate spreads in
     * Full page), then the two for both modes: Crop margins and Clean up scans.
     */
    @Composable
    private fun ModeSettings(state: ComicViewState) {
        val settings = state.settings
        if (state.mode == ComicReadingMode.PANELS) {
            StepperRow(
                label = "Panel margin",
                value = settings.margin.label,
                onPrevious = settings.margin.previous?.let { { viewModel.setPanelMargin(it) } },
                onNext = settings.margin.next?.let { { viewModel.setPanelMargin(it) } },
                previousSymbol = "‹",
                nextSymbol = "›",
            )
            HairlineDivider()
            StepperRow(
                label = "Panel transition",
                value = settings.transition.label,
                onPrevious = settings.transition.previous?.let { { viewModel.setPanelTransition(it) } },
                onNext = settings.transition.next?.let { { viewModel.setPanelTransition(it) } },
                previousSymbol = "‹",
                nextSymbol = "›",
            )
        } else {
            OnOffRow(
                label = "Rotate spreads",
                isOn = settings.rotateSpreads,
                onClick = { viewModel.setRotateSpreads(!settings.rotateSpreads) },
            )
        }
        HairlineDivider()
        OnOffRow(
            label = "Crop margins",
            isOn = settings.cropMargins,
            onClick = { viewModel.setCropMargins(!settings.cropMargins) },
        )
        HairlineDivider()
        StepperRow(
            label = "Clean up scans",
            value = state.cleanUp.label,
            onPrevious = state.cleanUp.previous?.let { { viewModel.setCleanUp(it) } },
            onNext = state.cleanUp.next?.let { { viewModel.setCleanUp(it) } },
            previousSymbol = "‹",
            nextSymbol = "›",
        )
        HairlineDivider()
    }

    /**
     * Past the last page, in the theme's colors: the comic's title, "Finished", and what comes
     * next in its folder. LAST PAGE goes back to the page; NEXT IN FOLDER opens the next comic
     * or note in place of this one; back leaves the comic.
     */
    @Composable
    private fun EndCard(state: ComicViewState) {
        val colors = LightThemeTokens.colors
        val next = state.next
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                // Taps on the card stay on it instead of reaching the page.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Finished"),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1.5f.gridUnitsAsDp()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LightText(text = title, variant = LightTextVariant.Subheading, align = TextAlign.Center)
                LightText(
                    text = "Finished",
                    variant = LightTextVariant.Copy,
                    align = TextAlign.Center,
                    color = LocalReaderAccent.current,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp(), bottom = 2f.gridUnitsAsDp()),
                )
                NextInFolderText(state)
            }
            LightBottomBar(
                items = listOfNotNull(
                    LightBarButton.Text(text = "LAST PAGE", onClick = viewModel::leaveEnd),
                    next?.let { LightBarButton.Text(text = "NEXT IN FOLDER", onClick = { goBack(it) }) },
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
        navigateTo(
            screenFactory = { ComicDetailsScreen(it, meta, title) },
            resultCallback = { removed -> if (removed) goBack() }, // the comic is gone: back to where it was opened
        )
    }
}

/**
 * FULL PAGE and PANELS under the slider. The one in use is underlined (as the Library's
 * BOOKS / COMICS bar), so it reads without color; tapping the other switches to it.
 */
@Composable
private fun ModeBar(current: ComicReadingMode, onSelect: (ComicReadingMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MODE_BAR_GRID_UNITS.gridUnitsAsDp()),
    ) {
        for (mode in ComicReadingMode.entries) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .lightClickable { onSelect(mode) },
                contentAlignment = Alignment.Center,
            ) {
                LightText(text = modeLabel(mode), variant = LightTextVariant.Button, underline = mode == current)
            }
        }
    }
}

private fun modeLabel(mode: ComicReadingMode): String = when (mode) {
    ComicReadingMode.FULL_PAGE -> "FULL PAGE"
    ComicReadingMode.PANELS -> "PANELS"
}

private const val MODE_BAR_GRID_UNITS = 4f

/** "Next in folder" and its title, "Last in this folder.", or "Preparing…" while it's looked for. */
@Composable
private fun NextInFolderText(state: ComicViewState) {
    val next = state.next
    when {
        !state.nextKnown -> LightText(text = "Preparing…", variant = LightTextVariant.Detail, lighten = true)
        next == null -> LightText(text = "Last in this folder.", variant = LightTextVariant.Detail, lighten = true)
        else -> {
            LightText(text = "Next in folder", variant = LightTextVariant.Detail, lighten = true)
            LightText(
                text = if (next is NextInFolder.Note) next.title + " (note)" else next.title,
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
            )
        }
    }
}

/** The zoom last drawn, and on which page cropped and laid out how: where the next animated move starts from. */
private class DrawnZoom {
    var pageIndex = -1
    var layout = PageLayout.WHOLE
    var crop: PixelRect? = null
    var zoom = PageZoom()

    /** The last drawn zoom, if it was of [picture]'s page cropped and laid out the same; else the page fitted. */
    fun on(page: Int, picture: ComicPagePicture?): PageZoom =
        if (page == pageIndex && picture?.layout == layout && picture.crop == crop) zoom else PageZoom()
}

/**
 * The page, drawn at its zoom: the fitted picture, and over it the sharp picture of the
 * zoomed-in part once it's read, both through the Clean up scans color filter. A move to a
 * panel slides there (at the Panel transition speed) from where the page was last drawn. Taps, double-taps, long presses, pinches and drags go to
 * [viewModel].
 */
@Composable
private fun ComicPage(state: ComicViewState, viewModel: ComicViewModel, modifier: Modifier) {
    val drawn = remember { DrawnZoom() }
    val start = remember(state.move) { drawn.on(state.pageIndex, state.picture) }
    val filter = remember(state.levels, state.cleanUp) {
        cleanUpMatrix(state.levels, state.cleanUp)?.let { ColorFilter.colorMatrix(ColorMatrix(it)) }
    }
    val duration = state.move.durationMs
    val progress = remember(state.move) { Animatable(if (duration > 0) 0f else 1f) }
    LaunchedEffect(state.move) {
        if (duration > 0) progress.animateTo(1f, tween(duration, easing = FastOutSlowInEasing))
    }
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
                    onLongPress = { viewModel.switchMode() },
                    onTap = { viewModel.tap(it.x, it.y) },
                )
            },
    ) {
        val picture = state.picture ?: return@Canvas
        val geometry = picture.geometry(size.width.toInt(), size.height.toInt())
        val zoom = geometry.between(start, state.zoom, progress.value)
        drawn.pageIndex = picture.pageIndex
        drawn.layout = picture.layout
        drawn.crop = picture.crop
        drawn.zoom = zoom
        drawPicture(picture, geometry, zoom, filter)
        val sharp = state.sharp?.takeIf {
            it.pageIndex == picture.pageIndex && it.layout == picture.layout && it.crop == picture.crop
        } ?: return@Canvas
        drawSharpArea(sharp, geometry, zoom, filter)
    }
}

private fun DrawScope.drawPicture(picture: ComicPagePicture, geometry: PageGeometry, zoom: PageZoom, filter: ColorFilter?) {
    drawImage(
        image = picture.image,
        dstOffset = IntOffset(geometry.pageLeft(zoom).roundToInt(), geometry.pageTop(zoom).roundToInt()),
        dstSize = IntSize(geometry.shownWidth(zoom).roundToInt(), geometry.shownHeight(zoom).roundToInt()),
        colorFilter = filter,
        filterQuality = FilterQuality.Medium,
    )
}

private fun DrawScope.drawSharpArea(sharp: SharpArea, geometry: PageGeometry, zoom: PageZoom, filter: ColorFilter?) {
    val scale = geometry.scale(zoom)
    val left = geometry.pageLeft(zoom) + sharp.region.left * scale
    val top = geometry.pageTop(zoom) + sharp.region.top * scale
    drawImage(
        image = sharp.image,
        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
        dstSize = IntSize((sharp.region.width * scale).roundToInt(), (sharp.region.height * scale).roundToInt()),
        colorFilter = filter,
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
