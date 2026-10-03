package com.thelightphone.listen.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.listen.storage.Settings
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.audio.LightVolumeLevel
import com.thelightphone.sdk.ui.LightModal
import com.thelightphone.sdk.ui.LightModalManager
import com.thelightphone.sdk.ui.LightProgressBar
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.CompletableDeferred

/**
 * What every Listen screen extends (through [com.thelightphone.listen.ListenScreen] or
 * directly): the volume buttons always change the media volume, never the ringer (which is
 * what LightOS does with them otherwise), and show [VolumeBar] for 2 s. The scroll-wheel
 * press and the camera button do nothing while Listen is open (LightOS would otherwise switch
 * the flashlight or open the camera). Turning the wheel still goes to LightOS: it's the
 * brightness control.
 */
abstract class VolumeKeyScreen<T>(sealedActivity: SealedLightActivity) : SimpleLightScreen<T>(sealedActivity) {

    private fun kept(keyCode: Int) = keyCode in IGNORED_KEYS

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (kept(keyCode)) return true
        val volume = lightContext.mediaVolume
        val level = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> volume.raise()
            KeyEvent.KEYCODE_VOLUME_DOWN -> volume.lower()
            else -> return false
        }
        LightModalManager.show(VolumeBar(level))
        return true
    }

    /** The key-up halves are kept from LightOS too, so it never sees half a press. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || kept(keyCode)

    /** Repeats of a held key go the same way as the press. */
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || kept(keyCode)

    private companion object {
        /**
         * The LP3 keys Listen keeps from LightOS and ignores (codes from the Light keyboard
         * library's LightDeviceKeys): camera button full press (27) and half press (80), and
         * the scroll-wheel press (319). The wheel's turns (317, 318) are not here: they still
         * reach LightOS, which uses them for brightness.
         */
        val IGNORED_KEYS = setOf(KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_FOCUS, 319)
    }
}

/**
 * A small "Volume" box with a bar near the top of the screen. It doesn't take taps, so the
 * screen underneath keeps working while it shows. Drawn over the screen by the SDK, outside
 * [ThemedScreen], so it picks up the theme itself.
 */
private class VolumeBar(private val level: LightVolumeLevel) : LightModal {
    private val dismissed = CompletableDeferred<Unit>()

    override val onExpired: () -> Unit = {}
    override fun dismiss() { dismissed.complete(Unit) }
    override suspend fun awaitDismiss() { dismissed.await() }

    @Composable
    override fun Content() {
        val settings by Settings.settings.collectAsState()
        val theme = ListenTheme.fromSavedName(settings.theme)
        LightTheme(colors = theme.colors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 7f.gridUnitsAsDp()),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LightThemeTokens.colors.background)
                        .border(1.dp, LightThemeTokens.colors.contentSecondary)
                        .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
                ) {
                    OneLine(text = volumeText(level), variant = LightTextVariant.Detail)
                    Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                    LightProgressBar(theme.colors, level.fraction)
                }
            }
        }
    }
}

/** "Volume 7 of 15", or "Volume off" at the bottom. */
internal fun volumeText(level: LightVolumeLevel): String =
    if (level.step <= 0) "Volume off" else "Volume ${level.step} of ${level.maxStep}"
