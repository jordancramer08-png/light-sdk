package com.thelightphone.reader

/**
 * Everything on the Reading Settings screen except the theme: how the page's text is set,
 * and whether the progress line shows under it. One set for all books. Plain Kotlin so it
 * can be unit-tested on the PC.
 */
data class ReaderSettings(
    val textSize: ReaderTextSize = ReaderTextSize.DEFAULT,
    val typeface: ReaderTypeface = ReaderTypeface.DEFAULT,
    val lineSpacing: ReaderLineSpacing = ReaderLineSpacing.DEFAULT,
    val margins: ReaderMargins = ReaderMargins.DEFAULT,
    val alignment: ReaderAlignment = ReaderAlignment.DEFAULT,
    val showProgressLine: Boolean = true,
)

/**
 * How lines sit between the margins. Left is the ragged right edge the reader always had;
 * Justified stretches each line to both margins and hyphenates long words.
 */
enum class ReaderAlignment(val label: String) {
    LEFT("Left"),
    JUSTIFIED("Justified");

    val previous: ReaderAlignment? get() = entries.getOrNull(ordinal - 1)
    val next: ReaderAlignment? get() = entries.getOrNull(ordinal + 1)

    companion object {
        val DEFAULT = LEFT
        fun fromSavedName(name: String?): ReaderAlignment = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** The page's font. Light is the SDK's own font; Serif and Sans are the phone's built-in ones. */
enum class ReaderTypeface(val label: String) {
    LIGHT("Light"),
    SERIF("Serif"),
    SANS("Sans");

    val previous: ReaderTypeface? get() = entries.getOrNull(ordinal - 1)
    val next: ReaderTypeface? get() = entries.getOrNull(ordinal + 1)

    companion object {
        val DEFAULT = LIGHT
        fun fromSavedName(name: String?): ReaderTypeface = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Line height as a multiple of the text size. Normal (1.45) is the spacing the reader always used. */
enum class ReaderLineSpacing(val label: String, val multiplier: Float) {
    COMPACT("Compact", 1.25f),
    NORMAL("Normal", 1.45f),
    RELAXED("Relaxed", 1.7f);

    val previous: ReaderLineSpacing? get() = entries.getOrNull(ordinal - 1)
    val next: ReaderLineSpacing? get() = entries.getOrNull(ordinal + 1)

    companion object {
        val DEFAULT = NORMAL
        fun fromSavedName(name: String?): ReaderLineSpacing = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * The left and right margin of the page, in grid units (the screen is 27 units wide).
 * Normal (1.5) is the margin the reader always used.
 */
enum class ReaderMargins(val label: String, val gridUnits: Float) {
    NARROW("Narrow", 0.75f),
    NORMAL("Normal", 1.5f),
    WIDE("Wide", 2.5f);

    val previous: ReaderMargins? get() = entries.getOrNull(ordinal - 1)
    val next: ReaderMargins? get() = entries.getOrNull(ordinal + 1)

    companion object {
        val DEFAULT = NORMAL
        fun fromSavedName(name: String?): ReaderMargins = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
