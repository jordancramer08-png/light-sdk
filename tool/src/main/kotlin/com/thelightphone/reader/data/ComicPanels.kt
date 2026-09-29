package com.thelightphone.reader.data

import com.thelightphone.reader.comics.PANEL_DETECTOR_VERSION
import com.thelightphone.reader.comics.PixelRect
import kotlinx.serialization.Serializable

/**
 * The panels found on a comic's pages, cached in `<filesDir>/comic-library/<id>/panels.json`
 * (CLAUDE.md 12), so each page is looked at once. [pages] is keyed by the page's name in the
 * CBZ. Panels found by another [version] of the detector are looked for again.
 */
@Serializable
data class ComicPanels(
    val version: Int = PANEL_DETECTOR_VERSION,
    val pages: Map<String, PagePanels> = emptyMap(),
)

/**
 * One page's panels, in reading order, in the page's own pixels ([pageWidth] × [pageHeight]).
 * No panels means the page is shown whole.
 */
@Serializable
data class PagePanels(
    val pageWidth: Int,
    val pageHeight: Int,
    val panels: List<PanelBox>,
) {
    val rects: List<PixelRect> get() = panels.map { it.rect }
}

/** One panel's edges, in page pixels (right and bottom just past the panel, as [PixelRect]). */
@Serializable
data class PanelBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val rect: PixelRect get() = PixelRect(left, top, right, bottom)

    companion object {
        fun of(rect: PixelRect) = PanelBox(rect.left, rect.top, rect.right, rect.bottom)
    }
}
