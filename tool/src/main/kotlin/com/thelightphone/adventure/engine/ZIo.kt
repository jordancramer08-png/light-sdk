package com.thelightphone.adventure.engine

/** Z-machine colors, with their Standard section 8.3.1 codes. */
enum class ZColor(val code: Int) {
    CURRENT(0), DEFAULT(1), BLACK(2), RED(3), GREEN(4), YELLOW(5),
    BLUE(6), MAGENTA(7), CYAN(8), WHITE(9),
}

/** The version 3 status line. Shows hours and minutes when [isTime], else score and moves. */
data class ZStatusLine(val location: String, val isTime: Boolean, val first: Int, val second: Int)

/** Where the upper window cursor is. Both count from 1. */
data class ZCursor(val line: Int, val column: Int)

/** What a screen can do. The engine copies this into the header so games can adapt. */
data class ZCapabilities(
    val screenWidth: Int = 80,
    val screenHeight: Int = 24,
    val color: Boolean = false,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val fixedPitch: Boolean = true,
    val splitScreen: Boolean = true,
    val timedInput: Boolean = false,
)

/**
 * Everything the engine asks of the outside world. The engine never touches a screen, keyboard
 * or file itself: the phone and the laptop tests each supply their own ZIo.
 */
interface ZIo {
    val capabilities: ZCapabilities get() = ZCapabilities()

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

    /** Where the upper window cursor is now. */
    fun cursor(): ZCursor

    /** Clears from the cursor to the end of its line. */
    fun eraseLine()

    /** True wraps text at word boundaries; false prints it exactly as it comes. */
    fun setBufferMode(buffered: Boolean)

    /** Sets the style bits: 0 roman, 1 reverse, 2 bold, 4 italic, 8 fixed pitch. */
    fun setTextStyle(style: Int)

    fun setColors(foreground: ZColor, background: ZColor)

    /** Asks where to save. Returns a slot name, or null if the player cancelled. */
    fun chooseSaveSlot(): String?

    /** Asks what to restore. Returns a slot name, or null if the player cancelled. */
    fun chooseRestoreSlot(): String?

    /** Stores a Quetzal saved game in a slot. Returns false if that failed. */
    fun writeSave(slot: String, data: ByteArray): Boolean

    /** Returns the saved game in a slot, or null if there is none or it cannot be read. */
    fun readSave(slot: String): ByteArray?

    /** Receives text sent to output stream 2, the transcript. Ignored by default. */
    fun transcript(text: String) {}

    /** A note for the developer, such as an unimplemented opcode. Players need not see it. */
    fun log(message: String)
}
