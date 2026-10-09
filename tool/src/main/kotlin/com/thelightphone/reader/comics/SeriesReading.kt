package com.thelightphone.reader.comics

/**
 * Reading a series (CLAUDE.md 12): its reading direction, how its spreads are treated, the
 * order its panels are read in, and the pages the reader set by hand. Plain Kotlin, unit-tested.
 */

/** Which way a series reads: pages and panels left to right, or right to left (manga). */
enum class ReadingDirection(val label: String) {
    LEFT_TO_RIGHT("Left to right"),
    RIGHT_TO_LEFT("Right to left");

    val previous: ReadingDirection? get() = entries.getOrNull(ordinal - 1)
    val next: ReadingDirection? get() = entries.getOrNull(ordinal + 1)
    val isRightToLeft: Boolean get() = this == RIGHT_TO_LEFT

    companion object {
        val DEFAULT = LEFT_TO_RIGHT
        fun fromSavedName(name: String?): ReadingDirection = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Whether wide pages are treated as two-page spreads: Auto (a page wider than tall is one) or Off (never). */
enum class SpreadSetting(val label: String) {
    AUTO("Auto"),
    OFF("Off");

    val previous: SpreadSetting? get() = entries.getOrNull(ordinal - 1)
    val next: SpreadSetting? get() = entries.getOrNull(ordinal + 1)

    companion object {
        val DEFAULT = AUTO
        fun fromSavedName(name: String?): SpreadSetting = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Is this page a spread? The reader's own choice for the page ([pageSaysSpread]) wins; else
 * with the series' spreads [setting] Auto a page wider than it is tall is one, and Off none is.
 */
fun isSpreadPage(width: Int, height: Int, setting: SpreadSetting, pageSaysSpread: Boolean?): Boolean =
    pageSaysSpread ?: (setting == SpreadSetting.AUTO && isSpread(width, height))

/**
 * Panels in reading order for the series' [direction]. [panels] come in left-to-right order
 * (as found). Right to left, each row is read from the right. On a [spread] (two pages side by
 * side), the first page's panels are all read before the second page's: the left half first
 * reading left to right, the right half first reading right to left. A panel belongs to the
 * half its middle is in. When a panel runs well across the middle (a wide panel over both
 * pages), the spread is one big page and is read as one.
 */
fun orderPanels(panels: List<PixelRect>, pageWidth: Int, direction: ReadingDirection, spread: Boolean): List<PixelRect> {
    if (panels.size <= 1) return panels
    val rtl = direction.isRightToLeft
    if (!spread) return inOrder(panels, pageWidth, rtl)
    val middle = pageWidth / 2
    val reach = pageWidth / SPREAD_CROSSING_SHARE
    if (panels.any { it.left < middle - reach && it.right > middle + reach }) return inOrder(panels, pageWidth, rtl)
    val (left, right) = panels.partition { (it.left + it.right) / 2 < middle }
    val first = if (rtl) right else left
    val second = if (rtl) left else right
    return inOrder(first, pageWidth, rtl) + inOrder(second, pageWidth, rtl)
}

/** A panel reaching more than a 20th of the page past the middle on both sides runs across it. */
private const val SPREAD_CROSSING_SHARE = 20

/** Left to right as [readingOrder] reads; right to left, the same done on the page seen in a mirror. */
private fun inOrder(panels: List<PixelRect>, pageWidth: Int, rtl: Boolean): List<PixelRect> {
    if (!rtl) return readingOrder(panels)
    val mirrored = panels.map { it.mirrored(pageWidth) }
    return readingOrder(mirrored).map { it.mirrored(pageWidth) }
}

private fun PixelRect.mirrored(pageWidth: Int) = PixelRect(pageWidth - right, top, pageWidth - left, bottom)

/**
 * What the reader set by hand for one comic's pages, keyed by the page's name in the CBZ:
 * [spreads] true = this page is a spread, false = it isn't (whatever its shape); [whole]
 * = show this page whole in Panels mode (its panels were wrong).
 */
data class PageOverrides(
    val spreads: Map<String, Boolean> = emptyMap(),
    val whole: Set<String> = emptySet(),
) {
    fun withSpread(page: String, spread: Boolean?): PageOverrides =
        copy(spreads = if (spread == null) spreads - page else spreads + (page to spread))

    fun withWhole(page: String, isWhole: Boolean): PageOverrides =
        copy(whole = if (isWhole) whole + page else whole - page)

    /** Saved as a set of strings: "spread:<page>", "single:<page>", "whole:<page>". */
    fun toSaved(): Set<String> =
        spreads.map { (page, spread) -> (if (spread) SPREAD else SINGLE) + page }.toSet() + whole.map { WHOLE + it }

    companion object {
        private const val SPREAD = "spread:"
        private const val SINGLE = "single:"
        private const val WHOLE = "whole:"

        /** Anything not understood is ignored. */
        fun fromSaved(saved: Set<String>?): PageOverrides {
            if (saved == null) return PageOverrides()
            val spreads = mutableMapOf<String, Boolean>()
            val whole = mutableSetOf<String>()
            for (entry in saved) {
                when {
                    entry.startsWith(SPREAD) -> spreads[entry.removePrefix(SPREAD)] = true
                    entry.startsWith(SINGLE) -> spreads[entry.removePrefix(SINGLE)] = false
                    entry.startsWith(WHOLE) -> whole += entry.removePrefix(WHOLE)
                }
            }
            return PageOverrides(spreads, whole)
        }
    }
}

/** What the PC's remove script asked to forget: comics (paths) and folders (paths). */
data class ForgetRequests(val comics: List<String>, val folders: List<String>)

/**
 * Reads the remove script's note: one line each, "comic<TAB><path>" or "folder<TAB><path>".
 * Anything else is ignored, and so is a folder "" (the whole comics folder).
 */
fun forgetRequests(text: String): ForgetRequests {
    val comics = mutableListOf<String>()
    val folders = mutableListOf<String>()
    for (line in text.lines()) {
        val kind = line.substringBefore('\t', "")
        val path = line.substringAfter('\t', "").trim().trim('/')
        if (path.isEmpty()) continue
        when (kind.trim()) {
            "comic" -> comics += path
            "folder" -> folders += path
        }
    }
    return ForgetRequests(comics.distinct(), folders.distinct())
}

/**
 * True when the DataStore key [keyName] holds a series setting of [folder] or of a folder under
 * it: `comic_series:<folder>:…` or `comic_clean_up:<folder>`. Never for the top folder "".
 */
fun isSeriesKeyOf(keyName: String, folder: String): Boolean {
    if (folder.isEmpty()) return false
    fun under(prefix: String, rest: String): Boolean =
        rest == folder || rest.startsWith("$folder/") || rest.startsWith("$folder:")
    return when {
        keyName.startsWith(SERIES_KEY_PREFIX) -> under(SERIES_KEY_PREFIX, keyName.removePrefix(SERIES_KEY_PREFIX))
        keyName.startsWith(CLEAN_UP_KEY_PREFIX) -> under(CLEAN_UP_KEY_PREFIX, keyName.removePrefix(CLEAN_UP_KEY_PREFIX))
        else -> false
    }
}

private const val SERIES_KEY_PREFIX = "comic_series:"
private const val CLEAN_UP_KEY_PREFIX = "comic_clean_up:"
