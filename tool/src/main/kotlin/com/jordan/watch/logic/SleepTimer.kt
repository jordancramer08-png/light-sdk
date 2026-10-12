package com.jordan.watch.logic

/** The sleep timer: 15/30/45/60 minutes, or the end of this movie/episode. */
object SleepTimer {

    /** Minutes offered. [END_OF_TITLE] means "stop when this one ends". */
    val choices = listOf(15, 30, 45, 60)
    const val END_OF_TITLE = -1

    fun label(choice: Int?): String = when (choice) {
        null -> "Off"
        END_OF_TITLE -> "End of this one"
        else -> "$choice min"
    }

    /** When a minutes choice runs out, or null for Off / end of title. */
    fun endsAt(choice: Int?, nowMs: Long): Long? =
        choice?.takeIf { it > 0 }?.let { nowMs + it * 60_000L }

    fun isDue(endsAtMs: Long?, nowMs: Long): Boolean = endsAtMs != null && nowMs >= endsAtMs

    /** The button text: "Sleep", "Sleep 12m", or "Sleep: end". */
    fun buttonText(choice: Int?, endsAtMs: Long?, nowMs: Long): String = when {
        choice == null -> "Sleep"
        choice == END_OF_TITLE -> "Sleep: end"
        endsAtMs == null -> "Sleep"
        else -> "Sleep ${((endsAtMs - nowMs).coerceAtLeast(0) + 59_999) / 60_000}m"
    }
}

/** The 10-second "Next up" countdown at the end of a collection film or episode. */
object NextUpCountdown {

    /** Whole seconds still to wait (10 down to 0). */
    fun secondsLeft(startedAtMs: Long, nowMs: Long): Int {
        val gone = ((nowMs - startedAtMs).coerceAtLeast(0) / 1000).toInt()
        return (NextUp.COUNTDOWN_SECONDS - gone).coerceAtLeast(0)
    }

    /** Offer the next one only if there is one and the sleep timer isn't waiting for the end. */
    fun shouldOffer(hasNext: Boolean, sleepChoice: Int?): Boolean =
        hasNext && sleepChoice != SleepTimer.END_OF_TITLE
}
