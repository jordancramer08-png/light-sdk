package com.thelightphone.reader

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.reader.data.ComicPositionRepository
import com.thelightphone.reader.data.ComicStore
import com.thelightphone.reader.data.ReadingStatusRepository
import com.thelightphone.reader.data.readerDatabase
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

class ComicFolderViewModel(
    private val folder: String,
    store: ComicStore,
    positionRepository: ComicPositionRepository,
    statusRepository: ReadingStatusRepository,
) : LightViewModel<Unit>() {

    val loader = ComicFolderLoader(store, positionRepository, statusRepository, viewModelScope)

    /** Runs every time the folder comes to the front, so new comics and new progress show up. */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        loader.loadFolder(folder)
    }

    /** Comics still being read wait until the folder is back in front. */
    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        loader.stop()
    }
}

/**
 * One folder inside the comics folder, opened from a folder row (CLAUDE.md 12): its
 * subfolders, then its comics and notes, in file-name order.
 */
class ComicFolderScreen(
    sealedActivity: SealedLightActivity,
    private val folder: String,
    private val title: String,
) : LightScreen<Unit, ComicFolderViewModel>(sealedActivity) {

    private val store = ComicStore(lightContext.filesDir)

    override val viewModelClass: Class<ComicFolderViewModel>
        get() = ComicFolderViewModel::class.java

    override fun createViewModel() = ComicFolderViewModel(
        folder,
        store,
        ComicPositionRepository.getInstance { lightContext.readerDatabase() },
        ReadingStatusRepository.getInstance { lightContext.readerDatabase() },
    )

    @Composable
    override fun Content() {
        val state by viewModel.loader.state.collectAsState()

        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(title),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            val loaded = state
            when {
                loaded == null -> Unit
                loaded.entries.isEmpty() -> CenteredMessage("This folder is empty.")
                else -> ComicEntryList(state = loaded, coverFile = store::coverFile, onOpen = { openComicEntry(it, store) })
            }
        }
    }
}

/** Opens a comics row: a folder in ComicFolderScreen, a comic in ComicScreen, a note in ComicNoteScreen. */
fun SimpleLightScreen<*>.openComicEntry(entry: ComicEntry, store: ComicStore) {
    when (entry) {
        is ComicEntry.Folder -> navigateTo(screenFactory = { ComicFolderScreen(it, entry.path, entry.title) })
        is ComicEntry.Note -> navigateTo(screenFactory = { ComicNoteScreen(it, entry.path, entry.title, store) })
        is ComicEntry.Comic -> {
            val meta = entry.meta ?: return
            navigateTo(screenFactory = { ComicScreen(it, meta, entry.title) })
        }
    }
}
