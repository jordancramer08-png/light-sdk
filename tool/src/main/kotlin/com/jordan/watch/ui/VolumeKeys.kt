package com.jordan.watch.ui

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
import com.jordan.watch.logic.PlayerMath
import com.jordan.watch.storage.WatchLibrary
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
 * What every Watch screen extends (copied from Listen): the volume buttons change the media
 * volume, never the ringer, and show [VolumeBar] for 2 s. The scroll-wheel press and the
 * camera button do nothing while Watch is open. Turning the wheel still goes to LightOS
 * (brightness).
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
         * Camera button full press (27) and half press (80), and the scroll-wheel press (319).
         * The wheel's turns (317, 318) are not here: LightOS uses them for brightness.
         */
        val IGNORED_KEYS = setOf(KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_FOCUS, 319)
    }
}

/** A small "Volume 7 of 15" box with a bar near the top. Doesn't take taps. */
private class VolumeBar(private val level: LightVolumeLevel) : LightModal {
    private val dismissed = CompletableDeferred<Unit>()

    override val onExpired: () -> Unit = {}
    override fun dismiss() { dismissed.complete(Unit) }
    override suspend fun awaitDismiss() { dismissed.await() }

    @Composable
    override fun Content() {
        val settings by WatchLibrary.repositories.settings.settings.collectAsState()
        val theme = WatchTheme.fromSavedName(settings.theme)
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
                    OneLine(text = PlayerMath.volumeText(level.step, level.maxStep), variant = LightTextVariant.Detail)
                    Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                    LightProgressBar(theme.colors, level.fraction)
                }
            }
        }
    }
}
