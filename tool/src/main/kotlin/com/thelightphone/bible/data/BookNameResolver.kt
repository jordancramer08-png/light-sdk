package com.thelightphone.bible.data

/**
 * Resolves any spelling of a book name - a translation's manifest key, a reading-plan
 * alias, or free-form input - to its canonical name from the plan's `canonicalBookOrder`.
 *
 * CLAUDE.md 8: the plan and the translation files don't use the same spellings
 * ("Song of Solomon" vs "Song of Songs", "Psalm" vs "Psalms"), so book names must never
 * be compared directly - always resolve both sides through this first.
 */
class BookNameResolver(plan: ReadingPlan) {

    private val canonicalByNormalized: Map<String, String> = buildMap {
        for ((canonical, aliases) in plan.bookAliases) {
            for (alias in aliases) put(normalize(alias), canonical)
        }
        // Canonical names are added last so they always win, even if some alias
        // elsewhere happened to normalize to the same string.
        for (canonical in plan.canonicalBookOrder) put(normalize(canonical), canonical)
    }

    /** Returns the canonical name for [name], or null if it doesn't match a known book. */
    fun resolve(name: String): String? = canonicalByNormalized[normalize(name)]

    companion object {
        /** Case-insensitive, ignoring periods and extra whitespace (CLAUDE.md 8). */
        fun normalize(name: String): String =
            name.lowercase().replace(".", "").trim().replace(Regex("\\s+"), " ")
    }
}
