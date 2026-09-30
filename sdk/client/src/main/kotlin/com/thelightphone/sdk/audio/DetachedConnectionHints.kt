package com.thelightphone.sdk.audio

import android.os.Bundle
import androidx.media3.session.SessionCommand

/**
 * What a tool's `MediaController` tells [LightAudioService] as it connects.
 *
 * The service is constructed by the system, not by us, so connection hints are
 * the only channel for per-connection context — and they arrive before the
 * controller is allowed to do anything.
 */
internal fun detachedConnectionHints(usage: LightAudioUsage): Bundle =
    Bundle().apply {
        putBoolean(TOOL_CONTROLLER_HINT, true)
        putString(USAGE_HINT, usage.name)
    }

internal fun Bundle.requestedUsage(): LightAudioUsage =
    getString(USAGE_HINT)
        ?.let { name -> LightAudioUsage.entries.firstOrNull { it.name == name } }
        ?: LightAudioUsage.Music

/**
 * Whether these hints came from a `LightAudioPlayer` rather than from someone
 * else holding a controller on the session.
 *
 * media3 connects a controller of its own to render the notification, the
 * platform connects legacy ones for media buttons, and a LightOS now-playing
 * surface will connect one to display playback. None of them should decide the
 * session's usage.
 */
internal fun Bundle.isToolController(): Boolean = getBoolean(TOOL_CONTROLLER_HINT, false)

/**
 * The custom session command a tool's player sends to change the live session's usage
 * (Music ↔ Speech), with the new usage in its arguments.
 */
internal val setUsageCommand = SessionCommand("com.thelightphone.sdk.audio.SET_USAGE", Bundle.EMPTY)

internal fun setUsageArgs(usage: LightAudioUsage): Bundle =
    Bundle().apply { putString(USAGE_HINT, usage.name) }

private const val USAGE_HINT = "com.thelightphone.sdk.audio.usage"
private const val TOOL_CONTROLLER_HINT = "com.thelightphone.sdk.audio.toolController"
