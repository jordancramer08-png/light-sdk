package com.thelightphone.listen.podcasts

import com.thelightphone.listen.ListenScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.listen.music.ListTopBar
import com.thelightphone.listen.playback.PlaybackHub
import com.thelightphone.listen.playback.formatTime
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.OneLine
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.UniformRow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

private const val CHAPTER_ROW_GRID_UNITS = 4.5f

/**
 * The playing episode's chapters: each with the time it starts, the current one marked "▶".
 * Opens at the current chapter. Tapping a chapter plays from its start and goes back to Now
 * Playing.
 */
class PodcastChaptersScreen(sealedActivity: SealedLightActivity) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val chapters by PlaybackHub.chapters.collectAsState()
        val current by PlaybackHub.chapter.collectAsState()
        val episode by PlaybackHub.episode.collectAsState()
        ThemedScreen {
            ListTopBar(title = "Chapters", onBack = { goBack() })
            val area = Modifier.weight(1f)
            if (episode?.hasChapters != true || chapters.isEmpty()) {
                CenteredMessage("This episode has no chapters.", modifier = area)
            } else {
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
                Box(modifier = area) {
                    LightLazyScrollView(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()),
                        listState = listState,
                        uniformItemHeightGridUnits = CHAPTER_ROW_GRID_UNITS,
                    ) {
                        itemsIndexed(chapters, key = { i, _ -> i }) { i, chapter ->
                            UniformRow(
                                heightGridUnits = CHAPTER_ROW_GRID_UNITS,
                                showDivider = i != chapters.lastIndex,
                                modifier = Modifier.lightClickable {
                                    PlaybackHub.seekToChapter(chapter)
                                    PlaybackHub.play()
                                    goBack()
                                },
                            ) {
                                Row(modifier = Modifier.fillMaxWidth().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                                    LightText(
                                        text = if (i == current) "▶" else "",
                                        variant = LightTextVariant.Detail,
                                        modifier = Modifier.padding(end = 0.5f.gridUnitsAsDp()),
                                    )
                                    OneLine(text = chapter.title, variant = LightTextVariant.Copy, modifier = Modifier.weight(1f))
                                    LightText(
                                        text = formatTime(chapter.startMs),
                                        variant = LightTextVariant.Detail,
                                        lighten = true,
                                        modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
