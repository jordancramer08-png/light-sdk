package com.thelightphone.adventure.engine

/**
 * Keeps track of what the engine could not do, or found odd. Each different gap reaches
 * [ZIo.log] once, however often it happens, and [report] gives the counts.
 *
 * Summaries start with one of: "unimplemented opcode", "error:", "stub:" (a feature deliberately
 * left out) or "unusual:" (the game did something odd that the engine handled).
 */
class GapLog(private val io: ZIo) {
    private class Gap(val firstAddress: Int) {
        var count = 0
    }

    private val gaps = LinkedHashMap<String, Gap>()

    fun note(summary: String, address: Int) {
        val gap = gaps.getOrPut(summary) {
            io.log("$summary at 0x${address.toString(16)}")
            Gap(address)
        }
        gap.count++
    }

    /** One line per gap, in the order they first happened. */
    fun report(): List<String> = gaps.map { (summary, gap) ->
        "${gap.count} x $summary (first at 0x${gap.firstAddress.toString(16)})"
    }
}
