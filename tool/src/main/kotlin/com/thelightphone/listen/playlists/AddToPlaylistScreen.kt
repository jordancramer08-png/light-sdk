package com.thelightphone.listen.playlists

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.music.ListTopBar
import com.thelightphone.listen.music.MusicLibrary
import com.thelightphone.listen.music.sortKey
import com.thelightphone.listen.ui.HairlineDivider
import com.thelightphone.listen.ui.OneLine
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * "Add to playlist…" for a song, an album or an artist ([entry]), like the Reader's Add to
 * list screen. Each playlist has an on/off switch: tapping it adds the entry at the end, or
 * takes it out again. "New playlist…" names a new playlist and puts the entry in it.
 */
class AddToPlaylistScreen(
    sealedActivity: SealedLightActivity,
    private val entry: PlaylistEntry,
) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val playlists by Playlists.playlists.collectAsState()
        val loaded by Playlists.loaded.collectAsState()
        val library by MusicLibrary.state.collectAsState()
        // Only a song needs the library for its name; an album or artist entry names itself.
        val what = if (entry.kind == PlaylistEntry.KIND_SONG) {
            matchEntry(entry, library)
        } else {
            EntryMatch(entry, emptyList(), emptyList())
        }
        ThemedScreen {
            ListTopBar(title = "Add to playlist", onBack = { goBack() })
            Box(modifier = Modifier.weight(1f)) {
                if (!loaded) return@Box
                LightScrollView(modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
                    OneLine(text = entryTitle(what), variant = LightTextVariant.Copy)
                    OneLine(text = entryKindLine(what), variant = LightTextVariant.Detail, lighten = true)
                    Box(modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp())) { HairlineDivider() }
                    ChoiceRow("New playlist…", LightIcons.ADD, onClick = ::openNewPlaylist)
                    playlists.sortedWith(compareBy({ sortKey(it.name) }, { it.id })).forEach { playlist ->
                        HairlineDivider()
                        ChoiceRow(
                            label = playlist.name,
                            icon = if (playlist.contains(entry)) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
                            onClick = { Playlists.toggle(playlist.id, entry) },
                        )
                    }
                }
            }
        }
    }

    private fun openNewPlaylist() {
        navigateTo(
            screenFactory = { PlaylistNameScreen(it, title = "New playlist") },
            resultCallback = { name -> Playlists.add(Playlists.create(name), entry) },
        )
    }
}

@Composable
private fun ChoiceRow(label: String, icon: LightIconConfiguration, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OneLine(text = label, variant = LightTextVariant.Copy, modifier = Modifier.weight(1f))
        LightIcon(icon = icon)
    }
}
