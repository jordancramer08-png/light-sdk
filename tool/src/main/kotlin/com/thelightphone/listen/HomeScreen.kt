package com.thelightphone.listen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.listen.music.MusicHomeScreen
import com.thelightphone.listen.music.MusicLibrary
import com.thelightphone.listen.storage.StorageAccess
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.MenuRow
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.UpdatingLine
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Listen's first screen: Music, Audiobooks and Now Playing. Without All files access it
 * shows how to grant it instead, and checks again each time Listen comes back to the front.
 */
@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    /** Null until checked, then whether Listen can read /sdcard/Listen. */
    private val hasAccess = MutableStateFlow<Boolean?>(null)

    override fun willShow() {
        super.willShow()
        val allowed = StorageAccess.hasAllFilesAccess()
        hasAccess.value = allowed
        if (allowed) MusicLibrary.refresh(lightContext.filesDir)
    }

    @Composable
    override fun Content() {
        val access by hasAccess.collectAsState()
        val library by MusicLibrary.state.collectAsState()
        ThemedScreen {
            LightTopBar(
                center = LightTopBarCenter.Text("Listen"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            when (access) {
                null -> Box(modifier = Modifier.weight(1f))
                false -> CenteredMessage(NO_ACCESS_MESSAGE, modifier = Modifier.weight(1f))
                true -> {
                    Column(modifier = Modifier.weight(1f)) {
                        MenuRow(title = "Music", onClick = ::openMusic)
                        MenuRow(title = "Audiobooks", detail = "Coming in a later update", onClick = null)
                        MenuRow(title = "Now Playing", detail = "Coming in a later update", onClick = null)
                    }
                    UpdatingLine(visible = library.updating)
                }
            }
        }
    }

    private fun openMusic() {
        navigateTo(screenFactory = { MusicHomeScreen(it) })
    }

    private companion object {
        const val NO_ACCESS_MESSAGE =
            "Listen can't see its files yet.\n\n" +
                "Run Listen-Phone-Sync.cmd on the PC and choose option 9.\n\n" +
                "Then close Listen and open it again."
    }
}
