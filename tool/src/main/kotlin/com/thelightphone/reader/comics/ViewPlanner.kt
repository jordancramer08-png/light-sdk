package com.thelightphone.reader.comics

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The view planner (CLAUDE.md 12, "Views"): between the detector and the viewer, a page's
 * panels (already in reading order) become the "views" Panels mode steps through. Tiny panels
 * side by side in a row are shown together, a panel far taller or wider than the screen is
 * shown in 2 or 3 parts, and a doubtful page starts with the whole page. Plain Kotlin, tested
 * on the PC. (The merge-and-divide idea comes from chika, github.com/batunii/chika; none of
 * its code is used.)
 */

/** A panel as the planner gets it: its box in page pixels and its ink along its longer side ([panelInk]; null = not known). */
data class PlannedPanel(val rect: PixelRect, val ink: List<Int>? = null)

/** The Light Phone III's screen, used before the viewer has measured its page area. */
const val PHONE_SCREEN_WIDTH = 1080
const val PHONE_SCREEN_HEIGHT = 1240

/** A panel shown bigger than this (zoom over fitted) counts as small: its neighbours could share the screen. */
const val SMALL_PANEL_ZOOM = 2.5f

/** Panels shown together must still be at least this big (zoom over fitted), so their text stays readable. */
const val GROUP_READABLE_ZOOM = 2.0f

/** A panel this many times longer than the screen's shape (either way) is divided... */
const val DIVIDE_SHAPE = 1.8f

/** ...unless it fits whole at this zoom or more anyway (big enough to read). */
const val DIVIDE_UNLESS_ZOOM = 2.0f

/** Past this shape it becomes 3 views, else 2. */
const val THREE_VIEWS_SHAPE = 2.8f

/** A cut is looked for this share of the panel's length either side of the even split point. */
const val CUT_REACH = 0.12f

/** How many slices of its longer side a panel's ink is measured in. */
const val INK_SLICES = 48

/**
 * The views of a page whose [panels] are in reading order. [crop] is the part of the page
 * shown (page pixels), the screen is [screenWidth] × [screenHeight] with [marginFraction] of
 * its shorter side left round a view (as [panelZoom]). [groupSmall]: the Group small panels
 * setting. [doubtful]: the detector wasn't sure, so the whole page (the [crop]) comes first.
 * Views come back in page pixels.
 */
fun planViews(
    panels: List<PlannedPanel>,
    crop: PixelRect,
    screenWidth: Int,
    screenHeight: Int,
    marginFraction: Float = PANEL_MARGIN_FRACTION,
    groupSmall: Boolean = true,
    doubtful: Boolean = false,
): List<PixelRect> {
    if (panels.isEmpty() || crop.isEmpty || screenWidth <= 0 || screenHeight <= 0) return emptyList()
    val screen = ViewScreen(crop, screenWidth, screenHeight, marginFraction)
    val views = mutableListOf<PixelRect>()
    if (doubtful) views += crop
    val groups = if (groupSmall) groupSmallPanels(panels, screen) else panels.map { listOf(it) }
    for (group in groups) {
        views += if (group.size == 1) divided(group[0], screen) else listOf(group.map { it.rect }.reduce(::spanOf))
    }
    return views
}

/** How big a box is shown: the fitted page's scale and the room left inside the margin. */
private class ViewScreen(crop: PixelRect, screenWidth: Int, screenHeight: Int, marginFraction: Float) {
    private val fit = min(screenWidth.toFloat() / crop.width, screenHeight.toFloat() / crop.height)
    private val margin = min(screenWidth, screenHeight) * marginFraction
    val roomX = screenWidth - 2 * margin
    val roomY = screenHeight - 2 * margin

    /** The zoom (over the fitted page) that fits [box] in the room, not capped. */
    fun zoomOf(box: PixelRect): Float = min(roomX / (box.width * fit), roomY / (box.height * fit))

    /** How many times taller ([tall]) or wider than the room's shape [box] is (1 = the same shape). */
    fun shape(box: PixelRect, tall: Boolean): Float {
        val boxShape = box.height.toFloat() / box.width
        val roomShape = roomY / roomX
        return if (tall) boxShape / roomShape else roomShape / boxShape
    }
}

/** Neighbours in reading order that are small, in one row, and still readable together become one group. */
private fun groupSmallPanels(panels: List<PlannedPanel>, screen: ViewScreen): List<List<PlannedPanel>> {
    val groups = mutableListOf<MutableList<PlannedPanel>>()
    for (panel in panels) {
        val last = groups.lastOrNull()
        if (last != null && canJoin(last, panel, panels, screen)) last += panel else groups += mutableListOf(panel)
    }
    return groups
}

private fun canJoin(group: List<PlannedPanel>, panel: PlannedPanel, all: List<PlannedPanel>, screen: ViewScreen): Boolean {
    if (screen.zoomOf(panel.rect) <= SMALL_PANEL_ZOOM || group.any { screen.zoomOf(it.rect) <= SMALL_PANEL_ZOOM }) return false
    val box = group.map { it.rect }.reduce(::spanOf)
    if (!sameRow(box, panel.rect)) return false
    val joined = spanOf(box, panel.rect)
    if (screen.zoomOf(joined) < GROUP_READABLE_ZOOM) return false
    // Never over a panel left out of the group (it would be shown twice, half cut off).
    return all.none { it !in group && it != panel && overlapArea(it.rect, joined) > it.rect.width.toLong() * it.rect.height / 10 }
}

/** Two boxes are in one row when their heights overlap by at least half the smaller. */
private fun sameRow(a: PixelRect, b: PixelRect): Boolean {
    val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
    return overlap * 2 >= min(a.height, b.height)
}

/** The panel, or 2 or 3 parts of it along its long side when fitting it whole would make it too small to read. */
private fun divided(panel: PlannedPanel, screen: ViewScreen): List<PixelRect> {
    val box = panel.rect
    if (screen.zoomOf(box) >= DIVIDE_UNLESS_ZOOM) return listOf(box)
    val tall = box.height >= box.width
    val shape = screen.shape(box, tall)
    if (shape < DIVIDE_SHAPE) return listOf(box)
    val parts = if (shape >= THREE_VIEWS_SHAPE) 3 else 2
    val start = if (tall) box.top else box.left
    val length = if (tall) box.height else box.width
    val cuts = (1 until parts).map { k -> start + cutAt(panel.ink, length, k.toFloat() / parts) }
    val edges = listOf(start) + cuts + (start + length)
    return edges.zipWithNext { from, to ->
        if (tall) PixelRect(box.left, from, box.right, to) else PixelRect(from, box.top, to, box.bottom)
    }
}

/**
 * Where to cut (pixels from the panel's start, of [length]) near [share] of the way along: the
 * emptiest slice of its [ink] within [CUT_REACH], each slice counted with its neighbours so the
 * gap between two lines of lettering in a balloon never looks empty. Ties go to the slice
 * nearest the even split. Without ink, the even split.
 */
fun cutAt(ink: List<Int>?, length: Int, share: Float): Int {
    val even = (length * share).roundToInt()
    if (ink == null || ink.size < 3) return even
    val slices = ink.size
    val aim = share * slices - 0.5f
    val from = ((share - CUT_REACH) * slices).roundToInt().coerceIn(1, slices - 2)
    val to = ((share + CUT_REACH) * slices).roundToInt().coerceIn(from, slices - 2)
    val best = (from..to).minWith(compareBy<Int>({ ink[it - 1] + 2 * ink[it] + ink[it + 1] }, { kotlin.math.abs(it - aim) }))
    return ((best + 0.5f) * length / slices).roundToInt().coerceIn(1, length - 1)
}

/**
 * A panel's ink along its longer side: [INK_SLICES] slices across it, each the share (per
 * thousand) of its pixels darker than 100 on [gray] (the page made small; [pageWidth] ×
 * [pageHeight] are the page's own pixels, as [panel]).
 */
fun panelInk(gray: GrayImage, pageWidth: Int, pageHeight: Int, panel: PixelRect): List<Int> {
    val sx = gray.width.toFloat() / pageWidth
    val sy = gray.height.toFloat() / pageHeight
    val left = (panel.left * sx).toInt().coerceIn(0, gray.width - 1)
    val top = (panel.top * sy).toInt().coerceIn(0, gray.height - 1)
    val right = (panel.right * sx).roundToInt().coerceIn(left + 1, gray.width)
    val bottom = (panel.bottom * sy).roundToInt().coerceIn(top + 1, gray.height)
    val tall = panel.height >= panel.width
    val length = if (tall) bottom - top else right - left
    val dark = IntArray(INK_SLICES)
    val total = IntArray(INK_SLICES)
    for (y in top until bottom) for (x in left until right) {
        val along = if (tall) y - top else x - left
        val slice = (along * INK_SLICES / length).coerceAtMost(INK_SLICES - 1)
        total[slice]++
        if (gray[x, y] < INK_DARK) dark[slice]++
    }
    return List(INK_SLICES) { if (total[it] == 0) 0 else dark[it] * 1000 / total[it] }
}

private const val INK_DARK = 100

private fun spanOf(a: PixelRect, b: PixelRect) = PixelRect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))

private fun overlapArea(a: PixelRect, b: PixelRect): Long {
    val w = min(a.right, b.right) - max(a.left, b.left)
    val h = min(a.bottom, b.bottom) - max(a.top, b.top)
    return if (w <= 0 || h <= 0) 0 else w.toLong() * h
}
