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
import androidx.compose.foundation.layout.size
import com.thelightphone.listen.artwork.ArtImage
import com.thelightphone.listen.artwork.ArtSize
import com.thelightphone.listen.artwork.artLetter
import com.thelightphone.listen.artwork.artSource
import com.thelightphone.listen.artwork.songArtSource
import com.thelightphone.listen.music.MusicLibrary
import com.thelightphone.listen.playback.PlaybackHub
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** The bar's height (grid units): song title and artist. */
private const val BAR_GRID_UNITS = 5f
private const val ART_GRID_UNITS = 3.6f

/**
 * The small bar at the bottom of list screens: the cover, the current song (or book chapter),
 * and a play/pause button.
 * Tapping the song opens Now Playing. Hidden while nothing is loaded.
 */
@Composable
fun NowPlayingBar(onOpen: () -> Unit) {
    val song by PlaybackHub.currentSong.collectAsState()
    val book by PlaybackHub.book.collectAsState()
    val chapters by PlaybackHub.chapters.collectAsState()
    val chapter by PlaybackHub.chapter.collectAsState()
    val playing by PlaybackHub.isPlaying.collectAsState()
    val library by MusicLibrary.state.collectAsState()
    val loadedBook = book
    val current = song
    if (loadedBook == null && current == null) return
    HairlineDivider()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_GRID_UNITS.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .lightClickable(onClick = onOpen)
                .padding(start = 1f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val artModifier = Modifier.size(ART_GRID_UNITS.gridUnitsAsDp())
            if (loadedBook != null) {
                ArtImage(loadedBook.artSource(), ArtSize.THUMB, artLetter(loadedBook.title), artModifier)
            } else if (current != null) {
                ArtImage(songArtSource(current, library.albumOf(current)), ArtSize.THUMB, artLetter(current.album), artModifier)
            }
            Column(modifier = Modifier.padding(start = 0.75f.gridUnitsAsDp())) {
                if (loadedBook != null) {
                    ScrollingLine(loadedBook.title, LightTextVariant.Copy, centered = false)
                    val line = chapters.getOrNull(chapter)?.title ?: loadedBook.author
                    ScrollingLine(line, LightTextVariant.Detail, lighten = true, centered = false)
                } else if (current != null) {
                    ScrollingLine(current.title, LightTextVariant.Copy, centered = false)
                    ScrollingLine(current.artist, LightTextVariant.Detail, lighten = true, centered = false)
                }
            }
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
