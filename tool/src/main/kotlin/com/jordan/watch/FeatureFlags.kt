package com.jordan.watch

/**
 * Switches for anything Phone Day hasn't confirmed yet. Each one is OFF until it's tested
 * on the phone, and the app works (with a simpler fallback) while it's off.
 */
object FeatureFlags {
    /** Landscape by drawing the video turned 90 degrees inside the portrait app. Off = portrait only. */
    const val ROTATED_PLAYER = true

    /**
     * Debug builds only: show a made-up library of 300 movies and 25 shows (held in memory,
     * nothing written to the phone) instead of /sdcard/Watch. Release builds ignore it.
     */
    const val FAKE_LIBRARY = false
}
