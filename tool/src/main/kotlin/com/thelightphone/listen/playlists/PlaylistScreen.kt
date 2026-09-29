package com.thelightphone.listen.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.artwork.ArtImage
import com.thelightphone.listen.artwork.ArtSize
import com.thelightphone.listen.artwork.ArtSource
import com.thelightphone.listen.artwork.artLetter
import com.thelightphone.listen.artwork.artSource
import com.thelightphone.listen.artwork.songArtSource
import com.thelightphone.listen.music.ArtAndText
import com.thelightphone.listen.music.ListTopBar
import com.thelightphone.listen.music.MusicLazyList
import com.thelightphone.listen.music.MusicLibrary
import com.thelightphone.listen.music.MusicLibraryState
import com.thelightphone.listen.music.SONG_ROW_GRID_UNITS
import com.thelightphone.listen.music.Song
import com.thelightphone.listen.music.lengthText
import com.thelightphone.listen.music.songCount
import com.thelightphone.listen.playback.PlaybackHub
import com.thelightphone.listen.playback.QueueSource
import com.thelightphone.listen.ui.ActionButton
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.DISABLED_ALPHA
import com.thelightphone.listen.ui.NowPlayingBar
import com.thelightphone.listen.ui.OneLine
import com.thelightphone.listen.ui.RowIconButton
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.UniformRow
import com.thelightphone.listen.ui.UpdatingLine
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlin.random.Random

private const val ENTRY_ART_GRID_UNITS = 3.8f

/** A playlist matched against the library right now. */
private data class Matched(val playlist: Playlist, val entries: List<EntryMatch>, val songs: List<Song>)

/** Plays [playlist]'s songs from [index] ([shuffle]: see [PlaybackHub.playSongs]). */
internal fun playPlaylist(playlist: Playlist, songs: List<Song>, index: Int, shuffle: Boolean? = null) {
    PlaybackHub.playSongs(songs, index, QueueSource(QueueSource.KIND_PLAYLIST, playlist.id), shuffle = shuffle)
}

/** Where an entry's cover comes from: the song's album, or the (first) album matched. */
internal fun EntryMatch.artSource(): ArtSource? {
    val song = songs.firstOrNull() ?: return null
    return if (entry.kind == PlaylistEntry.KIND_SONG) songArtSource(song, albums.firstOrNull()) else albums.firstOrNull()?.artSource()
}

/**
 * One playlist: how many songs and how long, Play and Shuffle, then its entries in order.
 * Each entry shows its cover, name, and what it is ("Album · Shane & Shane · 12 songs");
 * entries with nothing on the phone right now are faded and say "Not on phone".
 * Tapping a song plays the playlist from it; tapping an album or artist shows its songs.
 * The pencil switches to editing: each entry gets up, down and remove buttons.
 */
class PlaylistScreen(sealedActivity: SealedLightActivity, private val playlistId: String) : ListenScreen(sealedActivity) {

    private val editing = MutableStateFlow(false)

    @Composable
    override fun Content() {
        val playlists by Playlists.playlists.collectAsState()
        val loaded by Playlists.loaded.collectAsState()
        val library by MusicLibrary.state.collectAsState()
        val isEditing by editing.collectAsState()
        val playlist = playlists.firstOrNull { it.id == playlistId }
        val matched by produceState<Matched?>(null, playlist, library) {
            value = playlist?.let { withContext(Dispatchers.Default) { matched(it, library) } }
        }
        ThemedScreen {
            ListTopBar(
                title = playlist?.name ?: "Playlist",
                onBack = { goBack() },
                rightButton = if (playlist == null || playlist.entries.isEmpty()) {
                    null
                } else {
                    LightBarButton.LightIcon(
                        icon = if (isEditing) LightIcons.ACCEPT else LightIcons.PENCIL,
                        onClick = { editing.value = !isEditing },
                        contentDescription = if (isEditing) "Done" else "Edit",
                    )
                },
            )
            val area = Modifier.weight(1f)
            val shown = matched
            when {
                !loaded || !library.loaded -> Box(modifier = area)
                playlist == null -> CenteredMessage("This playlist was deleted.", modifier = area)
                playlist.entries.isEmpty() -> CenteredMessage(EMPTY, modifier = area)
                shown == null || shown.playlist.id != playlistId -> Box(modifier = area)
                else -> Column(modifier = area) {
                    if (!isEditing) Header(shown)
                    Box(modifier = Modifier.weight(1f)) {
                        MusicLazyList(tag = playlistId, rowGridUnits = SONG_ROW_GRID_UNITS) {
                            itemsIndexed(shown.entries, key = { i, m -> "$i|" + m.entry.matchKey }) { i, match ->
                                EntryRow(shown, i, match, isEditing)
                            }
                        }
                    }
                }
            }
            UpdatingLine(visible = library.updating)
            NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }

    /** "24 songs · 1 hr 32 min", then Play and Shuffle. */
    @Composable
    private fun Header(shown: Matched) {
        val songs = shown.songs
        Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
            LightText(
                text = if (songs.isEmpty()) {
                    "Nothing in this playlist is on the phone right now."
                } else {
                    songCount(songs.size) + " · " + lengthText(songs.sumOf { it.durationMs })
                },
                variant = LightTextVariant.Detail,
                lighten = true,
                maxLines = 1,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 0.75f.gridUnitsAsDp()),
                horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
            ) {
                val playable = songs.isNotEmpty()
                ActionButton("Play", LightIcons.PLAY, Modifier.weight(1f), enabled = playable) {
                    play(shown, 0, shuffle = false)
                }
                ActionButton("Shuffle", LightIcons.SHUFFLE, Modifier.weight(1f), enabled = playable) {
                    play(shown, Random.nextInt(songs.size), shuffle = true)
                }
            }
        }
    }

    @Composable
    private fun EntryRow(shown: Matched, index: Int, match: EntryMatch, isEditing: Boolean) {
        val last = shown.entries.lastIndex
        UniformRow(
            heightGridUnits = SONG_ROW_GRID_UNITS,
            showDivider = index != last,
            modifier = if (isEditing) Modifier else Modifier.lightClickable { open(shown, match) },
        ) {
            if (isEditing) {
                Row(modifier = Modifier.fillMaxWidth().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f).alpha(if (match.onPhone) 1f else DISABLED_ALPHA)) {
                        EntryLines(match)
                    }
                    val entry = match.entry
                    RowIconButton(LightIcons.UP, "Move up", enabled = index > 0) {
                        Playlists.move(playlistId, index, entry, up = true)
                    }
                    RowIconButton(LightIcons.DOWN, "Move down", enabled = index < last) {
                        Playlists.move(playlistId, index, entry, up = false)
                    }
                    RowIconButton(LightIcons.TRASH, "Remove") { Playlists.removeAt(playlistId, index, entry) }
                }
            } else {
                Box(modifier = Modifier.alpha(if (match.onPhone) 1f else DISABLED_ALPHA)) {
                    ArtAndText(
                        art = { size -> ArtImage(match.artSource(), ArtSize.THUMB, artLetter(entryTitle(match)), size) },
                        artGridUnits = ENTRY_ART_GRID_UNITS,
                    ) {
                        EntryLines(match)
                    }
                }
            }
        }
    }

    /** A song plays the playlist from there; an album or artist (or anything not on the phone) opens. */
    private fun open(shown: Matched, match: EntryMatch) {
        val song = match.songs.singleOrNull()
        if (match.entry.kind == PlaylistEntry.KIND_SONG && song != null) {
            play(shown, shown.songs.indexOfFirst { it.path == song.path }.coerceAtLeast(0), shuffle = null)
        } else {
            navigateTo(screenFactory = { PlaylistEntryScreen(it, playlistId, match.entry) })
        }
    }

    private fun play(shown: Matched, index: Int, shuffle: Boolean?) {
        if (shown.songs.isEmpty()) return
        playPlaylist(shown.playlist, shown.songs, index, shuffle)
        openNowPlaying()
    }

    private companion object {
        const val EMPTY = "This playlist is empty.\n\n" +
            "To add music, press and hold a song, album or artist, " +
            "or tap + at the top of an album, an artist or Now Playing."

        fun matched(playlist: Playlist, library: MusicLibraryState): Matched {
            val entries = matchEntries(playlist, library)
            return Matched(playlist, entries, playlistSongs(entries))
        }
    }
}

/** The entry's name, then what it is and how much ("Album · Shane & Shane · 12 songs"), or "Not on phone". */
@Composable
private fun EntryLines(match: EntryMatch) {
    OneLine(text = entryTitle(match), variant = LightTextVariant.Copy)
    val detail = when {
        !match.onPhone -> NOT_ON_PHONE + " · " + entryKindLine(match)
        match.entry.kind == PlaylistEntry.KIND_SONG -> entryKindLine(match)
        else -> entryKindLine(match) + " · " + songCount(match.songs.size)
    }
    OneLine(text = detail, variant = LightTextVariant.Detail, lighten = true)
}
