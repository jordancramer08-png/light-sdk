package com.jordan.watch.logic

/** Where to start a title again, and when it counts as watched. */
object Resume {

    const val REWIND_MS = 5_000L
    const val SAVE_EVERY_MS = 10_000L
    private const val WATCHED_FRACTION = 0.03
    private const val WATCHED_MAX_MS = 120_000L

    /**
     * Where to start when he reopens a title: 5 s before where he stopped. A title he had
     * finished starts from the beginning.
     */
    fun startPosition(savedMs: Long, durationMs: Long): Long {
        if (savedMs <= 0) return 0
        if (isWatched(savedMs, durationMs)) return 0
        return (savedMs - REWIND_MS).coerceAtLeast(0)
    }

    /** Watched once inside the last 3% or the last 2 minutes, whichever is smaller. */
    fun isWatched(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0) return false
        return positionMs >= durationMs - watchedTailMs(durationMs)
    }

    fun watchedTailMs(durationMs: Long): Long =
        minOf((durationMs * WATCHED_FRACTION).toLong(), WATCHED_MAX_MS)

    /** 0.0–1.0 for the Continue Watching progress bar. */
    fun progress(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0) return 0f
        return (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    /** True when 10 s have gone by since the last save while playing. */
    fun dueForSave(lastSavedAtMs: Long, nowMs: Long): Boolean = nowMs - lastSavedAtMs >= SAVE_EVERY_MS
}
