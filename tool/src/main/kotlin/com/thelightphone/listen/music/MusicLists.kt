package com.thelightphone.listen.music

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.listen.artwork.ArtImage
import com.thelightphone.listen.artwork.ArtSize
import com.thelightphone.listen.artwork.artLetter
import com.thelightphone.listen.artwork.artSource
import com.thelightphone.listen.artwork.songArtSource
import com.thelightphone.listen.ui.OneLine
import com.thelightphone.listen.ui.UniformRow
import com.thelightphone.listen.ui.tapOrHold
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Song rows (grid units, divider included): title, then artist, with a small album cover. */
const val SONG_ROW_GRID_UNITS = 5f
private const val SONG_ART_GRID_UNITS = 3.8f

/** Album and artist rows: three lines beside a bigger square cover. */
const val ALBUM_ROW_GRID_UNITS = 7f
private const val ALBUM_ART_GRID_UNITS = 5.6f

private const val ART_GAP_GRID_UNITS = 0.75f

/** A list sorted for a given sort [tag]; a new tag means the list starts again at the top. */
data class Sorted<T>(val tag: Any, val items: List<T>)

/**
 * [sort] applied to [input] off the main thread. Null until the first result; after that the
 * previous result stays on screen while a new one is worked out, so nothing flashes.
 */
@Composable
fun <I, T> rememberSorted(input: I, tag: Any, sort: (I) -> List<T>): Sorted<T>? =
    produceState<Sorted<T>?>(initialValue = null, input, tag) {
        value = Sorted(tag, withContext(Dispatchers.Default) { sort(input) })
    }.value

/**
 * The top bar of a list: back, the title, and at the right the sort button when [onSort] is
 * given, or else "+" (Add to playlist…) when [onAddToPlaylist] is, or else [rightButton].
 */
@Composable
fun ListTopBar(
    title: String,
    onBack: () -> Unit,
    onSort: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    rightButton: LightBarButton? = null,
) {
    LightTopBar(
        leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack),
        center = LightTopBarCenter.Text(title),
        rightButton = onSort?.let { LightBarButton.LightIcon(icon = LightIcons.REVERSE_ORDER, onClick = it) }
            ?: onAddToPlaylist?.let {
                LightBarButton.LightIcon(icon = LightIcons.ADD, onClick = it, contentDescription = "Add to playlist")
            }
            ?: rightButton,
        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
    )
}

/** A lazy list of fixed-height rows, recreated (so back at the top) when [tag] changes. */
@Composable
fun MusicLazyList(tag: Any, rowGridUnits: Float, content: LazyListScope.() -> Unit) {
    key(tag) {
        LightLazyScrollView(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
            uniformItemHeightGridUnits = rowGridUnits,
            content = content,
        )
    }
}

/**
 * Song rows: small cover, title, and [detail] (the artist, by default). Tapping plays from
 * that song; pressing and holding calls [onHold] (Add to playlist…).
 */
fun LazyListScope.songRows(
    songs: List<Song>,
    library: MusicLibraryState,
    onPlay: (List<Song>, Int) -> Unit,
    onHold: ((Song) -> Unit)? = null,
    detail: (Song) -> String = { it.artist },
) {
    itemsIndexed(songs, key = { _, song -> song.path }) { index, song ->
        UniformRow(
            heightGridUnits = SONG_ROW_GRID_UNITS,
            showDivider = index != songs.lastIndex,
            modifier = Modifier.tapOrHold(onHold = onHold?.let { { it(song) } }) { onPlay(songs, index) },
        ) {
            ArtAndText(
                art = { size ->
                    val album = library.albumOf(song)
                    ArtImage(songArtSource(song, album), ArtSize.THUMB, artLetter(song.album), size)
                },
                artGridUnits = SONG_ART_GRID_UNITS,
            ) {
                OneLine(text = song.title, variant = LightTextVariant.Copy)
                OneLine(text = detail(song), variant = LightTextVariant.Detail, lighten = true)
            }
        }
    }
}

/** Album rows: cover, title, artist, and year and song count. Press and hold for [onHold]. */
fun LazyListScope.albumRows(
    albums: List<Album>,
    onOpen: (Album) -> Unit,
    onHold: ((Album) -> Unit)? = null,
    lastHasDivider: Boolean = false,
) {
    itemsIndexed(albums, key = { _, album -> "album:" + album.key }) { index, album ->
        UniformRow(
            heightGridUnits = ALBUM_ROW_GRID_UNITS,
            showDivider = lastHasDivider || index != albums.lastIndex,
            modifier = Modifier.tapOrHold(onHold = onHold?.let { { it(album) } }) { onOpen(album) },
        ) {
            ArtAndText(
                art = { size -> ArtImage(album.artSource(), ArtSize.THUMB, artLetter(album.title), size) },
                artGridUnits = ALBUM_ART_GRID_UNITS,
            ) {
                OneLine(text = album.title, variant = LightTextVariant.Copy)
                OneLine(text = album.artist, variant = LightTextVariant.Detail, lighten = true)
                OneLine(text = albumDetail(album), variant = LightTextVariant.Detail, lighten = true)
            }
        }
    }
}

/** Artist rows: their first album's cover, name, and album and song counts. Press and hold for [onHold]. */
fun LazyListScope.artistRows(artists: List<Artist>, onOpen: (Artist) -> Unit, onHold: ((Artist) -> Unit)? = null) {
    itemsIndexed(artists, key = { _, artist -> "artist:" + artist.key }) { index, artist ->
        UniformRow(
            heightGridUnits = ALBUM_ROW_GRID_UNITS,
            showDivider = index != artists.lastIndex,
            modifier = Modifier.tapOrHold(onHold = onHold?.let { { it(artist) } }) { onOpen(artist) },
        ) {
            ArtAndText(
                art = { size -> ArtImage(artist.albums.first().artSource(), ArtSize.THUMB, artLetter(artist.name), size) },
                artGridUnits = ALBUM_ART_GRID_UNITS,
            ) {
                OneLine(text = artist.name, variant = LightTextVariant.Copy)
                OneLine(
                    text = albumCount(artist.albums.size) + " · " + songCount(artist.songCount),
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
            }
        }
    }
}

/** A square cover (drawn by [art] into the box it's given) at the left, its text beside it. */
@Composable
fun ArtAndText(
    art: @Composable (Modifier) -> Unit,
    artGridUnits: Float,
    text: @Composable () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
        art(Modifier.size(artGridUnits.gridUnitsAsDp()))
        Spacer(modifier = Modifier.width(ART_GAP_GRID_UNITS.gridUnitsAsDp()))
        Column(modifier = Modifier.weight(1f)) { text() }
    }
}

/** "2015 · 12 songs", or just "12 songs" without a year. */
fun albumDetail(album: Album): String =
    listOfNotNull(album.year.takeIf { it > 0 }?.toString(), songCount(album.songs.size)).joinToString(" · ")

/** "1 album", "12 albums". */
fun albumCount(count: Int): String = if (count == 1) "1 album" else "%,d albums".format(count)

/** "1 artist", "85 artists". */
fun artistCount(count: Int): String = if (count == 1) "1 artist" else "%,d artists".format(count)
