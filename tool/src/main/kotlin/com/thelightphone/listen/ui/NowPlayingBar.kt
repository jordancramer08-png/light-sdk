package com.thelightphone.listen.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.listen.playback.PlaybackHub
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** The bar's height (grid units): song title and artist. */
private const val BAR_GRID_UNITS = 5f

/**
 * The small bar at the bottom of list screens: the current song and a play/pause button.
 * Tapping the song opens Now Playing. Hidden while nothing is loaded.
 */
@Composable
fun NowPlayingBar(onOpen: () -> Unit) {
    val song by PlaybackHub.currentSong.collectAsState()
    val playing by PlaybackHub.isPlaying.collectAsState()
    val current = song ?: return
    HairlineDivider()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_GRID_UNITS.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .lightClickable(onClick = onOpen)
                .padding(start = 1f.gridUnitsAsDp()),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            OneLine(text = current.title, variant = LightTextVariant.Copy)
            OneLine(text = current.artist, variant = LightTextVariant.Detail, lighten = true)
        }
        Box(
            modifier = Modifier
                .width(BAR_GRID_UNITS.gridUnitsAsDp())
                .fillMaxHeight()
                .lightClickable(onClick = PlaybackHub::togglePlayPause),
            contentAlignment = Alignment.Center,
        ) {
            LightIcon(
                icon = if (playing) LightIcons.PAUSE else LightIcons.PLAY,
                size = 2f,
                contentDescription = if (playing) "Pause" else "Play",
            )
        }
    }
}
