package com.thelightphone.listen.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.music.ListTopBar
import com.thelightphone.listen.playback.PlaybackHub
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * The playing book's chapters, opened at the current one (marked). Tapping a chapter plays
 * from its start and goes back to Now Playing.
 */
class ChaptersScreen(sealedActivity: SealedLightActivity) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val chapters by PlaybackHub.chapters.collectAsState()
        val current by PlaybackHub.chapter.collectAsState()
        ThemedScreen {
            ListTopBar(title = "Chapters", onBack = { goBack() })
            val area = Modifier.weight(1f)
            if (chapters.isEmpty()) {
                CenteredMessage("No audiobook is playing.", modifier = area)
            } else {
                // A couple of rows above the current chapter stay in view.
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
                Box(modifier = area) {
                    LightLazyScrollView(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()),
                        listState = listState,
                        uniformItemHeightGridUnits = CHAPTER_ROW_GRID_UNITS,
                    ) {
                        itemsIndexed(chapters, key = { i, _ -> i }) { i, chapter ->
                            ChapterRow(
                                number = i + 1,
                                chapter = chapter,
                                isCurrent = i == current,
                                showDivider = i != chapters.lastIndex,
                                onClick = {
                                    PlaybackHub.seekToChapter(chapter)
                                    PlaybackHub.play()
                                    goBack()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
