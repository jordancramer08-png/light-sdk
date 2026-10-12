package com.jordan.watch.logic

/** The player's small sums: time labels, skips, scanning, orientation and the volume text. */
object PlayerMath {

    /** How long the controls stay up after the last touch while playing. */
    const val CONTROLS_HIDE_MS = 3_000L
    /** Holding a skip button this long starts scanning. */
    const val SCAN_START_MS = 400L
    /** While scanning, one jump every this many ms. */
    const val SCAN_TICK_MS = 250L

    /** The skip-ahead button (past intros and recaps). */
    const val SKIP_AHEAD_MS = 90_000L

    /** 65_000 -> "1:05", 3_725_000 -> "1:02:05". */
    fun timeLabel(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
    }

    /** "-1:02:05" for what's left; "-0:00" at the end or when the length is unknown. */
    fun remainingLabel(positionMs: Long, durationMs: Long): String =
        "-" + timeLabel((durationMs - positionMs).coerceAtLeast(0))

    /** [positionMs] moved by [deltaMs], kept inside the file (0 to [durationMs] when known). */
    fun skip(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
        val moved = (positionMs + deltaMs).coerceAtLeast(0)
        return if (durationMs > 0) moved.coerceAtMost(durationMs) else moved
    }

    /** Only 10, 15 or 30 s are offered; anything else in settings reads as 15. */
    fun skipSeconds(saved: Int): Int = if (saved in listOf(10, 15, 30)) saved else 15

    /**
     * How far each scan tick jumps, growing the longer the button is held: 5 s for the first
     * 2 s of holding, then 15 s, then 60 s after 6 s.
     */
    fun scanStepMs(heldMs: Long): Long = when {
        heldMs < 2_000 -> 5_000
        heldMs < 6_000 -> 15_000
        else -> 60_000
    }

    /** A seek-bar fraction (0–1) to a position. */
    fun fractionToMs(fraction: Float, durationMs: Long): Long =
        if (durationMs <= 0) 0 else (fraction.coerceIn(0f, 1f) * durationMs).toLong()

    /** A double tap on the left half skips back (-1), on the right half forward (+1). */
    fun doubleTapDirection(x: Float, width: Float): Int = if (x < width / 2) -1 else 1

    /**
     * How many quarter turns the player starts with: 0 = portrait, 1 = landscape.
     * [startIn] is "PORTRAIT", "LANDSCAPE" or "LAST" (then [lastOrientation] decides).
     * Without the rotated player it's always portrait.
     */
    fun startTurns(startIn: String, lastOrientation: String, rotationAllowed: Boolean): Int {
        if (!rotationAllowed) return 0
        val chosen = if (startIn == "LAST") lastOrientation else startIn
        return if (chosen == "LANDSCAPE") 1 else 0
    }

    /** 0 -> "PORTRAIT", 1 -> "LANDSCAPE", for settings.json. */
    fun orientationName(turns: Int): String = if (turns % 2 == 1) "LANDSCAPE" else "PORTRAIT"

    /** "Volume 7 of 15", or "Volume off" at the bottom. */
    fun volumeText(step: Int, maxStep: Int): String =
        if (step <= 0) "Volume off" else "Volume $step of $maxStep"

    private fun two(n: Long) = n.toString().padStart(2, '0')
}
