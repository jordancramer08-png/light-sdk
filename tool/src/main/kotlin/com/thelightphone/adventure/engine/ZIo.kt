package com.thelightphone.adventure.engine

/** Z-machine colors, with their Standard section 8.3.1 codes. */
enum class ZColor(val code: Int) {
    CURRENT(0), DEFAULT(1), BLACK(2), RED(3), GREEN(4), YELLOW(5),
    BLUE(6), MAGENTA(7), CYAN(8), WHITE(9),
}

/** The version 3 status line. Shows hours and minutes when [isTime], else score and moves. */
data class ZStatusLine(val location: String, val isTime: Boolean, val first: Int, val second: Int)

/**
 * Everything the engine asks of the outside world. The engine never touches a screen, keyboard
 * or file itself: the phone and the laptop tests each supply their own ZIo.
 */
interface ZIo {
    /** Prints text in the current window. */
    fun print(text: String)

    /**
     * Waits for a typed line of at most [maxLength] characters. A [timeoutTenths] above 0 asks
     * for a return after that many tenths of a second; null means it timed out.
     */
    fun readLine(maxLength: Int, timeoutTenths: Int = 0): String?

    /** Waits for one key and returns its ZSCII code, or null if it timed out. */
    fun readChar(timeoutTenths: Int = 0): Int?

    fun showStatusLine(status: ZStatusLine)

    /** Gives the upper window [lines] lines; 0 removes it. */
    fun splitWindow(lines: Int)

    /** Selects window 0 (lower) or 1 (upper). */
    fun setWindow(window: Int)

    /** Clears a window. -1 also unsplits the screen and -2 clears everything without unsplitting. */
    fun eraseWindow(window: Int)

    /** Moves the upper window cursor. [line] and [column] count from 1. */
    fun setCursor(line: Int, column: Int)

    /** Sets the style bits: 0 roman, 1 reverse, 2 bold, 4 italic, 8 fixed pitch. */
    fun setTextStyle(style: Int)

    fun setColors(foreground: ZColor, background: ZColor)

    /** Stores saved game data. Returns false if the player cancelled or it failed. */
    fun save(data: ByteArray): Boolean

    /** Returns saved game data, or null if the player cancelled or it failed. */
    fun restore(): ByteArray?
}
