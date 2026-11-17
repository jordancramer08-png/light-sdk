package com.thelightphone.reader.comics

/**
 * Everything looked at once per comic page (CLAUDE.md 12) and cached: the blank border to crop
 * off, the scan's levels for clean-up, and the panels. Plain Kotlin, so the phone and the PC
 * report run the very same steps:
 *
 * 1. the crop is found on the page as it is ([findCrop]);
 * 2. the levels are measured inside the crop ([measureLevels]);
 * 3. the page is leveled and made gray ([leveledGray]), and the gutter's gray read off its border;
 * 4. inside the crop, the connected-region detector ([findRegionPanels]) is tried first, then the
 *    XY-cut ([cutPanels], straight then slanted gutters), then (light gutters only) the XY-cut
 *    along drawn border lines; the first that passes [isReliableSplit] wins (the border cut must
 *    also tile its area, [tilesItsArea]), else the page has no panels;
 * 5. a contents / credits layout ([looksLikeFurnitureLayout]) or a text page
 *    ([isMostlyTextOutside]) is kept whole;
 * 6. each panel is scored ([panelDoubts]); a doubtful page is looked at again on a bigger picture
 *    ([larger], the second look), and kept whole when it's still doubtful.
 */

/** Which detector found a page's panels. */
enum class PanelMethod { REGIONS, XY_CUT, BORDERS, NONE }

/**
 * What [analysePage] found, in the page's own pixels: [crop] the part to show, [levels] for
 * clean-up, [panels] in reading order (empty = show the page whole). [gutter] is the gray the
 * panels were split along; [candidates] is what the region detector found when nothing was
 * reliable (only for the PC report). [note] says why a page was kept whole after a split was
 * found ("page furniture", "text page", "doubtful") or that the second look found the panels.
 */
class PageLook(
    val crop: PixelRect,
    val levels: PageLevels,
    val panels: List<PixelRect>,
    val method: PanelMethod,
    val gutter: Int,
    val candidates: List<PixelRect>,
    val note: String = "",
)

/** The detector wasn't sure of this page's panels: it's kept whole, and its [PageLook.candidates] are what it found. */
val PageLook.doubtful: Boolean get() = note == "doubtful"

/** The panels Panels mode steps through: those found, or on a doubtful page what was found (shown after the whole page). */
val PageLook.panelsToStep: List<PixelRect> get() = if (doubtful) candidates else panels

/** The second look is at this many pixels on the page's long side. */
const val SECOND_LOOK_LONG_SIDE = 1600

/**
 * Looks at one page. [image] is the page made small (about [PANEL_GRID_LONG_SIDE] on its long
 * side, [shrinkColorToLongSide]); [pageWidth] × [pageHeight] is the page's full size. [larger],
 * when given, hands the page at about [SECOND_LOOK_LONG_SIDE] for the second look (only asked
 * for on a doubtful page); without it a doubtful page is kept whole.
 */
fun analysePage(
    image: ColorImage,
    pageWidth: Int = image.width,
    pageHeight: Int = image.height,
    tuning: PanelTuning = PanelTuning(),
    cropTuning: CropTuning = CropTuning(),
    larger: (() -> ColorImage?)? = null,
): PageLook {
    val first = lookOnce(image, pageWidth, pageHeight, tuning, cropTuning)
    if (!first.doubtful) return first.look
    val bigger = larger?.invoke()
    if (bigger != null && bigger.width > image.width) {
        val second = lookOnce(bigger, pageWidth, pageHeight, tuning, cropTuning)
        if (second.look.panels.isNotEmpty() && !second.doubtful) return second.look.with(note = "second look")
    }
    return first.look.with(panels = emptyList(), method = PanelMethod.NONE, note = "doubtful", candidates = first.look.panels)
}

private class Look(val look: PageLook, val doubtful: Boolean)

private fun PageLook.with(
    panels: List<PixelRect> = this.panels,
    method: PanelMethod = this.method,
    note: String = this.note,
    candidates: List<PixelRect> = this.candidates,
) = PageLook(crop, levels, panels, method, gutter, candidates, note)

private fun lookOnce(image: ColorImage, pageWidth: Int, pageHeight: Int, tuning: PanelTuning, cropTuning: CropTuning): Look {
    val cropSmall = findCrop(image.gray(), cropTuning)
    val levels = measureLevels(image, cropSmall)
    val leveled = leveledGray(image, levels)
    val gutters = gutterCandidates(leveled, tuning)
    val content = leveled.crop(cropSmall)

    fun toPage(rects: List<PixelRect>): List<PixelRect> = rects.map {
        rectToPage(it.movedBy(cropSmall.left, cropSmall.top), image.width, image.height, pageWidth, pageHeight)
    }
    val crop = rectToPage(cropSmall, image.width, image.height, pageWidth, pageHeight)

    /** A reliable split, checked for page furniture and scored. */
    fun found(panels: List<PixelRect>, method: PanelMethod, gutter: Int): Look {
        val mask = PaperMask.of(content, gutter, tuning)
        val whole = when {
            looksLikeFurnitureLayout(panels, mask) -> "page furniture"
            isMostlyTextOutside(panels, mask) -> "text page"
            else -> null
        }
        if (whole != null) return Look(PageLook(crop, levels, emptyList(), PanelMethod.NONE, gutter, toPage(panels), whole), doubtful = false)
        return Look(PageLook(crop, levels, toPage(panels), method, gutter, emptyList()), isDoubtful(panels, mask, tuning))
    }

    val regionTries = gutters.map { it to findRegionPanels(content, it, tuning) }
    regionTries.firstOrNull { isReliableSplit(it.second, content, tuning) }?.let { (gutter, panels) ->
        return found(panels, PanelMethod.REGIONS, gutter)
    }
    for (gutter in gutters) {
        val pieces = cutPanels(content, gutter, tuning)
        if (isReliableSplit(pieces, content, tuning)) return found(pieces, PanelMethod.XY_CUT, gutter)
    }
    for (gutter in gutters.filter { it >= 128 }) {
        val pieces = cutPanels(content, gutter, tuning, borders = true)
        if (isReliableSplit(pieces, content, tuning) && tilesItsArea(pieces)) return found(pieces, PanelMethod.BORDERS, gutter)
    }
    val none = PageLook(crop, levels, emptyList(), PanelMethod.NONE, gutters.first(), toPage(regionTries.first().second))
    return Look(none, doubtful = false)
}

/**
 * True when [panels] fill at least [MIN_TILED_SHARE] of the box round them all (PanelsPlus's
 * acceptance idea): panels split along drawn lines tile their block; pieces of art cut along
 * dark strokes don't.
 */
fun tilesItsArea(panels: List<PixelRect>): Boolean {
    if (panels.isEmpty()) return false
    val box = panels.reduce(::union)
    val covered = panels.sumOf { it.width.toLong() * it.height }
    return covered >= MIN_TILED_SHARE * box.width.toLong() * box.height
}

private const val MIN_TILED_SHARE = 0.85
