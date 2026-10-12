package com.jordan.watch.logic

import kotlin.math.max
import kotlin.math.min

/** Fit = whole picture, black bars. Fill = no bars, edges cropped. Zoom = closer than Fill. */
enum class AspectMode { FIT, FILL, ZOOM }

/** How big the picture is drawn for each aspect choice and pinch. */
object Aspect {

    /** Zoom is this much bigger than Fill. */
    const val ZOOM_EXTRA = 1.33f
    const val MIN_PINCH = 1f
    const val MAX_PINCH = 4f

    /** Saved name -> mode; anything unknown is Fit. */
    fun fromName(name: String?): AspectMode = AspectMode.entries.firstOrNull { it.name == name } ?: AspectMode.FIT

    /** Fit -> Fill -> Zoom -> Fit. */
    fun next(mode: AspectMode): AspectMode = AspectMode.entries[(mode.ordinal + 1) % AspectMode.entries.size]

    fun label(mode: AspectMode): String = when (mode) {
        AspectMode.FIT -> "Fit"
        AspectMode.FILL -> "Fill"
        AspectMode.ZOOM -> "Zoom"
    }

    fun clampPinch(pinch: Float): Float = pinch.coerceIn(MIN_PINCH, MAX_PINCH)

    /**
     * Width and height to draw a [videoW] x [videoH] picture in a [boxW] x [boxH] space.
     * [pinch] (1–4) multiplies on top of the mode. Unknown video size fills the box.
     */
    fun pictureSize(boxW: Float, boxH: Float, videoW: Int, videoH: Int, mode: AspectMode, pinch: Float): Pair<Float, Float> {
        if (videoW <= 0 || videoH <= 0 || boxW <= 0f || boxH <= 0f) return boxW to boxH
        val fit = min(boxW / videoW, boxH / videoH)
        val fill = max(boxW / videoW, boxH / videoH)
        val base = when (mode) {
            AspectMode.FIT -> fit
            AspectMode.FILL -> fill
            AspectMode.ZOOM -> fill * ZOOM_EXTRA
        }
        val scale = base * clampPinch(pinch)
        return videoW * scale to videoH * scale
    }
}
