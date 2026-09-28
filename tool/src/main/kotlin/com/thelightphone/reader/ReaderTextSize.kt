package com.thelightphone.reader

/**
 * The five reading text sizes, smallest first. [scale] multiplies the SDK's Paragraph size;
 * Medium (1.0) is the size the reader used before this choice existed, and stays the default.
 * Plain Kotlin so it can be unit-tested on the PC.
 */
enum class ReaderTextSize(val label: String, val scale: Float) {
    SMALL("Small", 0.85f),
    MEDIUM("Medium", 1.0f),
    LARGE("Large", 1.15f),
    LARGER("Larger", 1.3f),
    EXTRA_LARGE("Extra large", 1.5f);

    /** One step smaller, or null at the smallest. */
    val smaller: ReaderTextSize? get() = entries.getOrNull(ordinal - 1)

    /** One step larger, or null at the largest. */
    val larger: ReaderTextSize? get() = entries.getOrNull(ordinal + 1)

    companion object {
        val DEFAULT = MEDIUM

        /** Turns a saved name back into a size; anything unknown falls back to the default. */
        fun fromSavedName(name: String?): ReaderTextSize =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
