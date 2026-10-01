package com.thelightphone.sdk.audio

import android.content.Context
import android.media.AudioManager
import android.util.Log

/** A volume as the platform counts it: [step] out of [maxStep]. */
data class LightVolumeLevel(val step: Int, val maxStep: Int) {
    /** 0.0 (silent) to 1.0 (loudest). */
    val fraction: Float
        get() = if (maxStep <= 0) 0f else (step.toFloat() / maxStep).coerceIn(0f, 1f)
}

/**
 * The media volume: the one every [LightAudioUsage.Music] and [LightAudioUsage.Speech]
 * player plays at. A tool that plays media can catch the volume keys in its screen's
 * `onKeyDown` and call [raise] / [lower], so the keys change the media volume instead of
 * the ringer while the tool is open. No system volume panel is shown; the tool draws its
 * own if it wants one.
 */
class LightMediaVolume internal constructor(context: Context) {

    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    val level: LightVolumeLevel
        get() = LightVolumeLevel(
            step = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
            maxStep = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        )

    /** One step louder. Returns the new level. */
    fun raise(): LightVolumeLevel = adjust(AudioManager.ADJUST_RAISE)

    /** One step quieter. Returns the new level. */
    fun lower(): LightVolumeLevel = adjust(AudioManager.ADJUST_LOWER)

    private fun adjust(direction: Int): LightVolumeLevel {
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        } catch (e: SecurityException) {
            // Do Not Disturb can refuse volume changes.
            Log.w(TAG, "Media volume change refused", e)
        }
        return level
    }

    private companion object {
        const val TAG = "LightMediaVolume"
    }
}
