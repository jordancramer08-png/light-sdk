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
 *    XY-cut ([cutPanels]); the first that passes [isReliableSplit] wins, else the page has no panels.
 */

/** Which detector found a page's panels. */
enum class PanelMethod { REGIONS, XY_CUT, NONE }

/**
 * What [analysePage] found, in the page's own pixels: [crop] the part to show, [levels] for
 * clean-up, [panels] in reading order (empty = show the page whole). [gutter] is the gray the
 * panels were split along; [candidates] is what the region detector found when nothing was
 * reliable (only for the PC report).
 */
class PageLook(
    val crop: PixelRect,
    val levels: PageLevels,
    val panels: List<PixelRect>,
    val method: PanelMethod,
    val gutter: Int,
    val candidates: List<PixelRect>,
)

/**
 * Looks at one page. [image] is the page made small (about [PANEL_GRID_LONG_SIDE] on its long
 * side, [shrinkColorToLongSide]); [pageWidth] × [pageHeight] is the page's full size.
 */
fun analysePage(
    image: ColorImage,
    pageWidth: Int = image.width,
    pageHeight: Int = image.height,
    tuning: PanelTuning = PanelTuning(),
    cropTuning: CropTuning = CropTuning(),
): PageLook {
    val cropSmall = findCrop(image.gray(), cropTuning)
    val levels = measureLevels(image, cropSmall)
    val leveled = leveledGray(image, levels)
    val gutters = gutterCandidates(leveled, tuning)
    val content = leveled.crop(cropSmall)

    fun toPage(rects: List<PixelRect>): List<PixelRect> = rects.map {
        rectToPage(it.movedBy(cropSmall.left, cropSmall.top), image.width, image.height, pageWidth, pageHeight)
    }
    val crop = rectToPage(cropSmall, image.width, image.height, pageWidth, pageHeight)
    fun look(panels: List<PixelRect>, method: PanelMethod, gutter: Int, candidates: List<PixelRect> = emptyList()) =
        PageLook(crop, levels, toPage(panels), method, gutter, toPage(candidates))

    val regionTries = gutters.map { it to findRegionPanels(content, it, tuning) }
    regionTries.firstOrNull { isReliableSplit(it.second, content, tuning) }?.let { (gutter, panels) ->
        return look(panels, PanelMethod.REGIONS, gutter)
    }
    for (gutter in gutters) {
        val pieces = cutPanels(content, gutter, tuning)
        if (isReliableSplit(pieces, content, tuning)) return look(pieces, PanelMethod.XY_CUT, gutter)
    }
    return look(emptyList(), PanelMethod.NONE, gutters.first(), regionTries.first().second)
}
