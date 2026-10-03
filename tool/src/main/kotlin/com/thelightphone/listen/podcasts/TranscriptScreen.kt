package com.thelightphone.listen.podcasts

import com.thelightphone.listen.ListenScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import com.thelightphone.listen.music.ListTopBar
import com.thelightphone.listen.playback.PlaybackHub
import com.thelightphone.listen.playback.formatTime
import com.thelightphone.listen.podcasts.transcripts.Transcript
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.NowPlayingBar
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** After the transcript is scrolled by hand, it waits this long before following the audio again. */
private const val FOLLOW_PAUSE_MS = 6_000L

/**
 * An episode's transcript. Timed transcripts mark the line being spoken, keep it in view as
 * the episode plays, and jump there when a line is tapped (while this episode is the one
 * loaded). Untimed ones are plain text. Only the lines on screen are laid out, so a
 * three-hour transcript scrolls as smoothly as a short one.
 */
class TranscriptScreen(
    sealedActivity: SealedLightActivity,
    private val showId: String,
    private val episodeId: String,
) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val loaded by produceState<TranscriptLoad>(TranscriptLoad.Loading, showId, episodeId) {
            value = Podcasts.loadTranscript(showId, episodeId)
        }
        val episode by PlaybackHub.episode.collectAsState()
        val isThisEpisode = episode?.isEpisode(showId, episodeId) == true
        ThemedScreen {
            ListTopBar(title = "Transcript", onBack = { goBack() })
            val area = Modifier.weight(1f)
            when (val l = loaded) {
                TranscriptLoad.Loading -> CenteredMessage("Loading the transcript…", modifier = area)
                is TranscriptLoad.Failed -> CenteredMessage(l.message, modifier = area)
                is TranscriptLoad.Loaded -> Box(modifier = area) { Lines(l.transcript, follow = isThisEpisode && l.transcript.timed) }
            }
            NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }

    @Composable
    private fun Lines(transcript: Transcript, follow: Boolean) {
        val position by PlaybackHub.positionMs.collectAsState()
        val playing by PlaybackHub.isPlaying.collectAsState()
        val current = if (follow) transcript.lineAt(position) else -1
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 1).coerceAtLeast(0))
        val dragged by listState.interactionSource.collectIsDraggedAsState()
        var lastUserScroll by remember { mutableLongStateOf(0L) }
        if (dragged) lastUserScroll = System.currentTimeMillis()
        // Keep the spoken line in view (a line above it for context), unless Jordan has just
        // scrolled to read something else.
        LaunchedEffect(current, playing) {
            if (current < 0 || !playing) return@LaunchedEffect
            if (System.currentTimeMillis() - lastUserScroll < FOLLOW_PAUSE_MS) return@LaunchedEffect
            val visible = listState.layoutInfo.visibleItemsInfo
            val shown = visible.any { it.index == current } && visible.lastOrNull()?.index != current
            if (!shown) listState.animateScrollToItem((current - 1).coerceAtLeast(0))
        }
        LightLazyScrollView(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()),
            listState = listState,
            // Lines differ in height, so the scrollbar is approximate.
            uniformItemHeightGridUnits = 4f,
        ) {
            itemsIndexed(transcript.lines, key = { i, _ -> i }) { i, line ->
                val start = line.startMs
                val tap = if (follow && start != null) {
                    Modifier.lightClickable {
                        PlaybackHub.seekTo(start)
                        PlaybackHub.play()
                    }
                } else {
                    Modifier
                }
                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).then(tap).padding(vertical = 0.5f.gridUnitsAsDp())) {
                    // A bar beside the line being spoken.
                    Box(
                        modifier = Modifier
                            .width(0.3f.gridUnitsAsDp())
                            .fillMaxHeight()
                            .then(if (i == current) Modifier.background(LightThemeTokens.colors.content) else Modifier),
                    )
                    Column(modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp())) {
                        val heading = listOfNotNull(line.speaker, start?.let(::formatTime)).joinToString(" · ")
                        if (heading.isNotEmpty()) LightText(text = heading, variant = LightTextVariant.Detail, lighten = true)
                        LightText(text = line.text, variant = LightTextVariant.Copy, lighten = follow && current >= 0 && i != current)
                    }
                }
            }
        }
    }
}

/** A transcript being read: still loading, ready, or why not. */
sealed interface TranscriptLoad {
    data object Loading : TranscriptLoad
    data class Loaded(val transcript: Transcript) : TranscriptLoad
    data class Failed(val message: String) : TranscriptLoad
}
