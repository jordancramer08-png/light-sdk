package com.jordan.watch

/**
 * Switches for anything Phone Day hasn't confirmed yet. Each one is OFF until it's tested
 * on the phone, and the app works (with a simpler fallback) while it's off.
 */
object FeatureFlags {
    /** Landscape by drawing the video turned 90 degrees inside the portrait app. Off = portrait only. */
    const val ROTATED_PLAYER = false
}
