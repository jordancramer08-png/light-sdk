package com.thelightphone.reader.data

import com.thelightphone.reader.comics.PANEL_DETECTOR_VERSION
import com.thelightphone.reader.comics.PageLevels
import com.thelightphone.reader.comics.PixelRect
import kotlinx.serialization.Serializable

/**
 * What was found on a comic's pages (panels, crop, levels), cached in
 * `<filesDir>/comic-library/<id>/panels.json` (CLAUDE.md 12), so each page is looked at once.
 * [pages] is keyed by the page's name in the CBZ. Pages looked at by another [version] of the
 * analysis are looked at again.
 */
@Serializable
data class ComicPanels(
    val version: Int = PANEL_DETECTOR_VERSION,
    val pages: Map<String, PagePanels> = emptyMap(),
)

/**
 * What was found on one page, in the page's own pixels ([pageWidth] × [pageHeight]): its
 * panels in reading order (none means the page is shown whole), the part left once the blank
 * border is cropped off ([crop]; null = the whole page), and its [levels] for scan clean-up
 * (null = not known, drawn as it is).
 */
@Serializable
data class PagePanels(
    val pageWidth: Int,
    val pageHeight: Int,
    val panels: List<PanelBox>,
    val crop: PanelBox? = null,
    val levels: LevelsBox? = null,
) {
    val rects: List<PixelRect> get() = panels.map { it.rect }
}

/** A page's darkest and brightest level per channel, as cached (see [PageLevels]). */
@Serializable
data class LevelsBox(val black: List<Int>, val white: List<Int>) {
    val levels: PageLevels? get() =
        if (black.size == 3 && white.size == 3) PageLevels(black[0], black[1], black[2], white[0], white[1], white[2]) else null

    companion object {
        fun of(levels: PageLevels) = LevelsBox(
            black = listOf(levels.blackRed, levels.blackGreen, levels.blackBlue),
            white = listOf(levels.whiteRed, levels.whiteGreen, levels.whiteBlue),
        )
    }
}

/** One panel's edges, in page pixels (right and bottom just past the panel, as [PixelRect]). */
@Serializable
data class PanelBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val rect: PixelRect get() = PixelRect(left, top, right, bottom)

    companion object {
        fun of(rect: PixelRect) = PanelBox(rect.left, rect.top, rect.right, rect.bottom)
    }
}
