package com.thelightphone.listen.music

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.artwork.ArtImage
import com.thelightphone.listen.artwork.ArtSize
import com.thelightphone.listen.artwork.artLetter
import com.thelightphone.listen.artwork.artSource
import com.thelightphone.listen.playback.PlaybackHub
import com.thelightphone.listen.playback.QueueSource
import com.thelightphone.listen.playback.formatTime
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.HairlineDivider
import com.thelightphone.listen.ui.NowPlayingBar
import com.thelightphone.listen.ui.OneLine
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.UpdatingLine
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlin.random.Random

/** The album cover at the top of the album screen (grid units square). */
private const val COVER_GRID_UNITS = 10f

/**
 * An album: its cover, title, artist, year and length, Play and Shuffle, then the tracks in
 * disc and track order (with "Disc 2" headings when there's more than one disc). Tapping a
 * track plays the album from there.
 */
class AlbumScreen(sealedActivity: SealedLightActivity, private val albumKey: String) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val library by MusicLibrary.state.collectAsState()
        val album = library.album(albumKey)
        ThemedScreen {
            ListTopBar(title = "Album", onBack = { goBack() })
            val area = Modifier.weight(1f)
            when {
                !library.loaded -> Box(modifier = area)
                album == null -> CenteredMessage("This album isn't on the phone any more.", modifier = area)
                else -> Box(modifier = area) { AlbumDetails(album) }
            }
            UpdatingLine(visible = library.updating)
            NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }

    @Composable
    private fun AlbumDetails(album: Album) {
        LightScrollView(modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
            Header(album)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 1f.gridUnitsAsDp()),
                horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
            ) {
                ActionButton("Play", LightIcons.PLAY, Modifier.weight(1f)) { play(album, 0, shuffle = false) }
                ActionButton("Shuffle", LightIcons.SHUFFLE, Modifier.weight(1f)) {
                    play(album, Random.nextInt(album.songs.size), shuffle = true)
                }
            }
            HairlineDivider()
            var disc = -1
            album.songs.forEachIndexed { index, song ->
                if (album.hasSeveralDiscs && song.discOrder != disc) {
                    disc = song.discOrder
                    LightText(
                        text = "Disc $disc",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 1f.gridUnitsAsDp(), bottom = 0.25f.gridUnitsAsDp()),
                    )
                    HairlineDivider()
                }
                TrackRow(song, showArtist = groupKey(song.artist) != groupKey(album.artist)) {
                    play(album, index, shuffle = false)
                }
                if (index != album.songs.lastIndex) HairlineDivider()
            }
        }
    }

    private fun play(album: Album, index: Int, shuffle: Boolean) {
        PlaybackHub.playSongs(album.songs, index, QueueSource(QueueSource.KIND_ALBUM, album.key), shuffle = shuffle)
        openNowPlaying()
    }
}

/** The cover at the left; title (up to two lines), artist, and year · songs · length beside it. */
@Composable
private fun Header(album: Album) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ArtImage(
            source = album.artSource(),
            size = ArtSize.LARGE,
            letter = artLetter(album.title),
            modifier = Modifier.size(COVER_GRID_UNITS.gridUnitsAsDp()),
        )
        Spacer(modifier = Modifier.width(1f.gridUnitsAsDp()))
        Column(modifier = Modifier.weight(1f)) {
            LightText(
                text = album.title,
                variant = LightTextVariant.Subheading,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(0.25f.gridUnitsAsDp()))
            OneLine(text = album.artist, variant = LightTextVariant.Copy, lighten = true)
            OneLine(text = albumDetail(album), variant = LightTextVariant.Detail, lighten = true)
            OneLine(text = lengthText(album.durationMs), variant = LightTextVariant.Detail, lighten = true)
        }
    }
}

/** A framed button with an icon and a word, a big tap target. */
@Composable
private fun ActionButton(label: String, icon: LightIconConfiguration, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .height(4f.gridUnitsAsDp())
            .border(1.dp, LightThemeTokens.colors.contentSecondary)
            .lightClickable(onClickLabel = label, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightIcon(icon = icon, size = 1.5f, contentDescription = null)
        Spacer(modifier = Modifier.width(0.5f.gridUnitsAsDp()))
        LightText(text = label, variant = LightTextVariant.Copy)
    }
}

/** Track number, title (and the performer when it isn't the album artist), and length. */
@Composable
private fun TrackRow(song: Song, showArtist: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = if (song.track > 0) song.track.toString() else "",
            variant = LightTextVariant.Detail,
            lighten = true,
            maxLines = 1,
            modifier = Modifier.width(2f.gridUnitsAsDp()),
        )
        Column(modifier = Modifier.weight(1f)) {
            OneLine(text = song.title, variant = LightTextVariant.Copy)
            if (showArtist) OneLine(text = song.artist, variant = LightTextVariant.Detail, lighten = true)
        }
        if (song.durationMs > 0) {
            LightText(
                text = formatTime(song.durationMs),
                variant = LightTextVariant.Detail,
                lighten = true,
                maxLines = 1,
                modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
            )
        }
    }
}

/** "48 min", "1 hr 12 min". */
fun lengthText(ms: Long): String {
    val minutes = ((ms + 30_000) / 60_000).toInt()
    return if (minutes < 60) "$minutes min" else "${minutes / 60} hr ${minutes % 60} min"
}
