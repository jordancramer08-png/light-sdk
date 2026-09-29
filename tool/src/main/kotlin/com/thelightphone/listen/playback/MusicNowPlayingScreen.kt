package com.thelightphone.listen.playback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.artwork.ArtImage
import com.thelightphone.listen.artwork.ArtSize
import com.thelightphone.listen.artwork.artLetter
import com.thelightphone.listen.artwork.songArtSource
import com.thelightphone.listen.music.MusicLibrary
import com.thelightphone.listen.music.Song
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.LocalListenAccent
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.LightRepeatMode
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * Music Now Playing: album art, title, artist and album, a seek bar with times, and
 * shuffle / previous / play-pause / next / repeat.
 */
class MusicNowPlayingScreen(sealedActivity: SealedLightActivity) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val song by PlaybackHub.currentSong.collectAsState()
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Now Playing"),
            )
            val current = song
            if (current == null) {
                CenteredMessage(
                    "Nothing is playing.\n\nPick a song in Music.",
                    modifier = Modifier.weight(1f),
                )
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    Artwork(current)
                }
                Column(modifier = Modifier.padding(horizontal = 1.5f.gridUnitsAsDp())) {
                    SongLines(current)
                    SeekBarWithTimes(current)
                    Controls()
                }
            }
        }
    }
}

/** The album's art (decoded about 800 px, from the same cache as the album screen). */
@Composable
private fun Artwork(song: Song) {
    val library by MusicLibrary.state.collectAsState()
    ArtImage(
        source = songArtSource(song, library.albumOf(song)),
        size = ArtSize.LARGE,
        letter = artLetter(song.album),
        modifier = Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true),
        placeholder = { letter, modifier -> FramedPlaceholder(letter, modifier) },
    )
}

/** No art: the album's first letter in a thin frame. */
@Composable
private fun FramedPlaceholder(letter: String, modifier: Modifier) {
    Box(
        modifier = modifier.border(1.dp, LightThemeTokens.colors.contentSecondary),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = letter, variant = LightTextVariant.Title, lighten = true)
    }
}

@Composable
private fun SongLines(song: Song) {
    val message by PlaybackHub.message.collectAsState()
    CenteredLine(song.title, LightTextVariant.Heading)
    CenteredLine(song.artist, LightTextVariant.Copy, lighten = true)
    // The album line doubles as the place for "Can't play this file".
    CenteredLine(message ?: song.album, LightTextVariant.Detail, lighten = message == null)
}

@Composable
private fun CenteredLine(text: String, variant: LightTextVariant, lighten: Boolean = false) {
    LightText(
        text = text,
        variant = variant,
        lighten = lighten,
        align = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A seek bar you can tap or drag; the times show the drag position while dragging. */
@Composable
private fun SeekBarWithTimes(song: Song) {
    val position by PlaybackHub.positionMs.collectAsState()
    val playerDuration by PlaybackHub.durationMs.collectAsState()
    val duration = if (playerDuration > 0) playerDuration else song.durationMs
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction
        ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    val track = LightThemeTokens.colors.contentSecondary
    val accent = LocalListenAccent.current
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(3f.gridUnitsAsDp())
            .pointerInput(duration) {
                val inset = THUMB_RADIUS.dp.toPx()
                val toFraction = { x: Float -> ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f) }
                detectTapGestures { offset ->
                    if (duration > 0) PlaybackHub.seekTo((toFraction(offset.x) * duration).toLong())
                }
            }
            .pointerInput(duration) {
                val inset = THUMB_RADIUS.dp.toPx()
                val toFraction = { x: Float -> ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f) }
                detectHorizontalDragGestures(
                    onDragStart = { offset -> dragFraction = toFraction(offset.x) },
                    onDragEnd = {
                        dragFraction?.let { if (duration > 0) PlaybackHub.seekTo((it * duration).toLong()) }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                ) { change, _ -> dragFraction = toFraction(change.position.x) }
            },
    ) {
        val inset = THUMB_RADIUS.dp.toPx()
        val y = size.height / 2
        val x = inset + (size.width - 2 * inset) * fraction
        drawLine(track, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = 2.dp.toPx())
        drawLine(accent, Offset(inset, y), Offset(x, y), strokeWidth = 4.dp.toPx())
        drawCircle(accent, radius = inset, center = Offset(x, y))
    }
    val shown = dragFraction?.let { (it * duration).toLong() } ?: position
    Row(modifier = Modifier.fillMaxWidth()) {
        LightText(text = formatTime(shown), variant = LightTextVariant.Detail, lighten = true)
        LightText(
            text = if (duration > 0) formatTime(duration) else "",
            variant = LightTextVariant.Detail,
            lighten = true,
            align = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

private const val THUMB_RADIUS = 7

@Composable
private fun Controls() {
    val playing by PlaybackHub.isPlaying.collectAsState()
    val shuffle by PlaybackHub.shuffle.collectAsState()
    val repeat by PlaybackHub.repeat.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.5f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlButton(LightIcons.SHUFFLE, if (shuffle) "Shuffle on" else "Shuffle off", active = shuffle,
            onClick = PlaybackHub::toggleShuffle)
        ControlButton(LightIcons.REWIND, "Previous", onClick = PlaybackHub::previous)
        ControlButton(
            if (playing) LightIcons.PAUSE else LightIcons.PLAY,
            if (playing) "Pause" else "Play",
            iconSize = 3f,
            onClick = PlaybackHub::togglePlayPause,
        )
        ControlButton(LightIcons.FAST_FORWARD, "Next", onClick = PlaybackHub::next)
        ControlButton(
            LightIcons.LOOP,
            when (repeat) {
                LightRepeatMode.Off -> "Repeat off"
                LightRepeatMode.All -> "Repeat all"
                LightRepeatMode.One -> "Repeat one"
            },
            active = repeat != LightRepeatMode.Off,
            badge = if (repeat == LightRepeatMode.One) "1" else null,
            onClick = PlaybackHub::cycleRepeat,
        )
    }
}

/** A big tap target around an icon; [active] = false draws it faded (shuffle/repeat off). */
@Composable
private fun ControlButton(
    icon: LightIconConfiguration,
    label: String,
    active: Boolean = true,
    iconSize: Float = 2f,
    badge: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(4.5f.gridUnitsAsDp())
            .lightClickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LightIcon(
            icon = icon,
            size = iconSize,
            contentDescription = label,
            modifier = Modifier.alpha(if (active) 1f else INACTIVE_ALPHA),
        )
        if (badge != null) {
            LightText(
                text = badge,
                variant = LightTextVariant.Fine,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

private const val INACTIVE_ALPHA = 0.35f

/** 83000 → "1:23"; an hour or more → "1:02:03". */
fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
