package com.thelightphone.reader.comics

/**
 * The comic viewer's settings, changed from its overlay (CLAUDE.md 12, part 5). One set for
 * every comic. Plain Kotlin, unit-tested.
 */

/** Around a panel in Panels mode, this share of the screen's shorter side is left free on every side. */
enum class PanelMargin(val label: String, val fraction: Float) {
    TIGHT("Tight", 0.01f),
    NORMAL("Normal", PANEL_MARGIN_FRACTION),
    ROOMY("Roomy", 0.06f);

    val previous: PanelMargin? get() = entries.getOrNull(ordinal - 1)
    val next: PanelMargin? get() = entries.getOrNull(ordinal + 1)

    companion object {
        val DEFAULT = NORMAL
        fun fromSavedName(name: String?): PanelMargin = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** How the move from one panel to the next is drawn: a jump, or a slide over [moveMs]. */
enum class PanelTransition(val label: String, val moveMs: Int) {
    OFF("Off", 0),
    FAST("Fast", 150),
    SMOOTH("Smooth", PANEL_MOVE_MS);

    val previous: PanelTransition? get() = entries.getOrNull(ordinal - 1)
    val next: PanelTransition? get() = entries.getOrNull(ordinal + 1)

    companion object {
        val DEFAULT = SMOOTH
        fun fromSavedName(name: String?): PanelTransition = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * [rotateSpreads]: in Full page mode, a two-page spread is shown turned a quarter turn to fill
 * the screen. [cropMargins]: each page's blank border is cut off (both modes). Clean up scans
 * isn't here: it's remembered per folder ([CleanUp]).
 */
data class ComicViewSettings(
    val margin: PanelMargin = PanelMargin.DEFAULT,
    val transition: PanelTransition = PanelTransition.DEFAULT,
    val rotateSpreads: Boolean = false,
    val cropMargins: Boolean = true,
)
