package com.jordan.watch.logic

import kotlin.math.abs
import kotlin.math.roundToInt

/** Playback speeds 0.25x–3.0x in 0.25 steps. */
object Speed {

    const val MIN = 0.25f
    const val MAX = 3.0f
    const val STEP = 0.25f

    /** 0.25, 0.5, ... 3.0 (12 steps). */
    val steps: List<Float> = (1..12).map { it * STEP }

    val quick: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    /** Rounds any value to the nearest allowed step, so a broken saved value can't escape. */
    fun snap(speed: Float): Float {
        if (speed.isNaN()) return 1f
        val clamped = speed.coerceIn(MIN, MAX)
        return (clamped / STEP).roundToInt() * STEP
    }

    fun faster(speed: Float): Float = snap(snap(speed) + STEP)

    fun slower(speed: Float): Float = snap(snap(speed) - STEP)

    /** 1.0 -> "1x", 1.5 -> "1.5x", 1.25 -> "1.25x". */
    fun label(speed: Float): String {
        val hundredths = (snap(speed) * 100).roundToInt()
        val whole = hundredths / 100
        val rest = hundredths % 100
        val text = when {
            rest == 0 -> "$whole"
            rest % 10 == 0 -> "$whole.${rest / 10}"
            else -> "$whole.$rest"
        }
        return "${text}x"
    }

    fun isNormal(speed: Float): Boolean = abs(snap(speed) - 1f) < 0.001f
}
