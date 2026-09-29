package com.thelightphone.reader.comics

import kotlin.math.max
import kotlin.math.min

/**
 * Panel-by-panel reading (CLAUDE.md 12, part 4): which panel a tap goes to, and how a page is
 * zoomed so one panel fills the screen. Plain Kotlin, so it runs in PC tests. Panel numbers
 * here are 0-based; -1 means the whole page.
 */

/** How the comic viewer shows pages: whole, or one panel at a time. */
enum class ComicReadingMode {
    FULL_PAGE,
    PANELS;

    companion object {
        val DEFAULT = PANELS

        /** Turns a saved name back into a mode; anything unknown means Panels. */
        fun fromSavedName(name: String?): ComicReadingMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Around a panel, this share of the screen's shorter side is left free on every side (the Normal margin). */
const val PANEL_MARGIN_FRACTION = 0.03f

/** How long the move from one panel to the next takes (the Smooth transition). */
const val PANEL_MOVE_MS = 250

/** After a page's last panel, the whole page shows this long before the next page. */
const val WHOLE_PAGE_PAUSE_MS = 700L

/** Where a tap goes in Panels mode. */
sealed interface PanelStep {
    /** Move to this panel of the same page. */
    data class ToPanel(val panel: Int) : PanelStep

    /** The last panel was shown: show the whole page a moment, then the next page. */
    data object WholePageThenNext : PanelStep

    data object NextPage : PanelStep
    data object PreviousPage : PanelStep
}

/**
 * The step for a tap forward (right side) or back (left side) while [panel] of [panelCount]
 * panels is shown. A page with no panels (or shown whole) turns straight to the next or previous page.
 */
fun panelStep(forward: Boolean, panel: Int, panelCount: Int): PanelStep = when {
    panelCount == 0 || panel < 0 -> if (forward) PanelStep.NextPage else PanelStep.PreviousPage
    forward && panel < panelCount - 1 -> PanelStep.ToPanel(panel + 1)
    forward -> PanelStep.WholePageThenNext
    panel > 0 -> PanelStep.ToPanel(panel - 1)
    else -> PanelStep.PreviousPage
}

/** The panel a page opens on: the first when reading forward, the last when going back; -1 with no panels. */
fun enteringPanel(panelCount: Int, forward: Boolean): Int = when {
    panelCount == 0 -> -1
    forward -> 0
    else -> panelCount - 1
}

/** The panel a comic reopens on: the saved one (1-based, 0 = whole page means the first), never past the last. */
fun openingPanelIndex(savedPanel: Int?, panelCount: Int): Int =
    if (panelCount == 0) -1 else ((savedPanel ?: 1).coerceAtLeast(1) - 1).coerceAtMost(panelCount - 1)

/** What is saved for a panel: 1-based, 0 for the whole page. */
fun savedPanelNumber(panel: Int): Int = if (panel < 0) 0 else panel + 1

/** One panel of one page (both 0-based). */
data class PanelSpot(val page: Int, val panel: Int)

/**
 * Where the next forward tap will land on a panel, so its sharp picture can be read ahead:
 * the next panel of this page, else the first panel of the next page. [panel] -1 is the
 * whole page; [nextPagePanels] is null when the next page's panels aren't known (or there is
 * no next page). Null when the next tap won't land on a panel.
 */
fun nextPanelSpot(page: Int, panel: Int, panelCount: Int, nextPagePanels: Int?): PanelSpot? = when {
    panel >= 0 && panel < panelCount - 1 -> PanelSpot(page, panel + 1)
    nextPagePanels != null && nextPagePanels > 0 -> PanelSpot(page + 1, 0)
    else -> null
}

/**
 * The zoom that shows [panel] (in page pixels) as big as it fits on the screen with a margin
 * of [marginFraction] of the screen's shorter side, centred. Never below fitted or above
 * [MAX_ZOOM]. Unlike pinching, the page may sit off-centre past its edge here, so a panel at
 * the page's edge is centred too.
 */
fun PageGeometry.panelZoom(panel: PixelRect, marginFraction: Float = PANEL_MARGIN_FRACTION): PageZoom {
    if (panel.isEmpty) return PageZoom()
    val fit = scale(PageZoom())
    val margin = min(screenWidth, screenHeight) * marginFraction
    val roomX = screenWidth - 2 * margin
    val roomY = screenHeight - 2 * margin
    val zoom = min(roomX / (panel.width * fit), roomY / (panel.height * fit)).coerceIn(1f, MAX_ZOOM)
    return zoomCentredOn(zoom, (panel.left + panel.right) / 2f, (panel.top + panel.bottom) / 2f)
}

/**
 * The zoom a fraction [t] (0 to 1) of the way from [from] to [to], for the move between
 * panels. The part of the page on screen slides and grows or shrinks evenly, so the move
 * looks like one smooth camera move.
 */
fun PageGeometry.between(from: PageZoom, to: PageZoom, t: Float): PageZoom {
    if (t >= 1f) return to
    if (t <= 0f) return from
    val fit = scale(PageZoom())
    // Page pixels per screen pixel: the width of what's on screen, which changes evenly.
    val spanFrom = 1f / scale(from)
    val spanTo = 1f / scale(to)
    val span = spanFrom + (spanTo - spanFrom) * t
    val (fromX, fromY) = centreOf(from)
    val (toX, toY) = centreOf(to)
    return zoomCentredOn(
        zoom = max(1f / (span * fit), 0.0001f),
        centreX = fromX + (toX - fromX) * t,
        centreY = fromY + (toY - fromY) * t,
    )
}

/** The page point (in page pixels) at the middle of the screen. */
private fun PageGeometry.centreOf(zoom: PageZoom): Pair<Float, Float> {
    val scale = scale(zoom)
    return (imageWidth / 2f - zoom.panX / scale) to (imageHeight / 2f - zoom.panY / scale)
}

/** The zoom at [zoom] with the page point ([centreX], [centreY]) in the middle of the screen. */
private fun PageGeometry.zoomCentredOn(zoom: Float, centreX: Float, centreY: Float): PageZoom {
    val scale = scale(PageZoom(zoom))
    // "+ 0f" turns -0 into 0, as in PageGeometry.clamp.
    return PageZoom(zoom, scale * (imageWidth / 2f - centreX) + 0f, scale * (imageHeight / 2f - centreY) + 0f)
}
