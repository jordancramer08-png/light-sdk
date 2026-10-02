package com.thelightphone.listen.podcasts

import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.artwork.ArtImage
import com.thelightphone.listen.artwork.ArtSize
import com.thelightphone.listen.artwork.artLetter
import com.thelightphone.listen.books.Book
import com.thelightphone.listen.playback.CenteredLine
import com.thelightphone.listen.playback.ControlButton
import com.thelightphone.listen.playback.FramedPlaceholder
import com.thelightphone.listen.playback.LoadedEpisode
import com.thelightphone.listen.playback.PODCAST_SPEEDS
import com.thelightphone.listen.playback.PlaybackHub
import com.thelightphone.listen.playback.QualityLine
import com.thelightphone.listen.playback.formatLine
import com.thelightphone.listen.playback.SeekBarWithTimes
import com.thelightphone.listen.playback.speedText
import com.thelightphone.listen.podcasts.store.EpisodeState
import com.thelightphone.listen.podcasts.store.episodeKey
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.ChoiceScreen
import com.thelightphone.listen.ui.ScrollingLine
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * Podcast Now Playing: channel art, episode title, then the current chapter (or the show),
 * a seek bar for the whole episode, previous chapter / back 15 s / play-pause / forward 30 s
 * / next chapter, and buttons for the speed, the transcript and the played mark. The list
 * icon at the top opens the chapters. Chapter controls only appear when the episode has
 * chapters; Transcript only when it has one.
 */
class PodcastNowPlayingScreen(sealedActivity: SealedLightActivity) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val book by PlaybackHub.book.collectAsState()
        val episode by PlaybackHub.episode.collectAsState()
        val states by Podcasts.states.collectAsState()
        val current = book
        val loaded = episode
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Now Playing"),
                rightButton = loaded?.takeIf { it.hasChapters }?.let {
                    LightBarButton.LightIcon(icon = LightIcons.LIST, onClick = ::openChapters, contentDescription = "Chapters")
                },
            )
            if (current == null || loaded == null) {
                CenteredMessage("No episode is playing.\n\nPick one in Podcasts.", modifier = Modifier.weight(1f))
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    ArtImage(
                        source = Podcasts.artSource(loaded.showId),
                        size = ArtSize.LARGE,
                        letter = artLetter(current.author),
                        modifier = Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true),
                        placeholder = { letter, modifier -> FramedPlaceholder(letter, modifier) },
                    )
                }
                Column(modifier = Modifier.padding(horizontal = 1.5f.gridUnitsAsDp())) {
                    TitleLines(current, loaded)
                    EpisodeSeekBar()
                    if (loaded.streaming) {
                        StreamingLine()
                    } else {
                        current.files.firstOrNull()?.let { QualityLine(fileName = it.path, fileBytes = it.size, durationMs = it.durationMs) }
                    }
                    Controls(loaded.hasChapters)
                    val state = states[episodeKey(loaded.showId, loaded.episodeId)] ?: EpisodeState()
                    Buttons(loaded, state)
                }
            }
        }
    }

    @Composable
    private fun Buttons(loaded: LoadedEpisode, state: EpisodeState) {
        val speed by PlaybackHub.speed.collectAsState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 1f.gridUnitsAsDp()),
            horizontalArrangement = Arrangement.spacedBy(0.75f.gridUnitsAsDp()),
        ) {
            FramedTextButton(speedText(speed), Modifier.weight(1f), ::openSpeed)
            if (loaded.hasTranscript) {
                FramedTextButton("Transcript", Modifier.weight(1.4f)) { openTranscript(loaded) }
            }
            FramedTextButton(if (state.played) "Unplayed" else "Played", Modifier.weight(1.2f)) {
                if (state.played) Podcasts.markUnplayed(loaded.showId, loaded.episodeId) else Podcasts.markPlayed(loaded.showId, loaded.episodeId)
            }
        }
    }

    private fun openChapters() = navigateTo(screenFactory = { PodcastChaptersScreen(it) })

    private fun openTranscript(loaded: LoadedEpisode) =
        navigateTo(screenFactory = { TranscriptScreen(it, loaded.showId, loaded.episodeId) })

    private fun openSpeed() {
        navigateTo(
            screenFactory = {
                ChoiceScreen(it, "Speed", "Podcast speed (all episodes)", PODCAST_SPEEDS.map(::speedText), PODCAST_SPEEDS.indexOf(PlaybackHub.speed.value))
            },
            resultCallback = { i -> PODCAST_SPEEDS.getOrNull(i)?.let(PlaybackHub::setSpeed) },
        )
    }
}

/** The episode's title, then the current chapter, the show, or a message ("Can't play this file"). */
@Composable
private fun TitleLines(book: Book, loaded: LoadedEpisode) {
    val chapters by PlaybackHub.chapters.collectAsState()
    val chapter by PlaybackHub.chapter.collectAsState()
    val message by PlaybackHub.message.collectAsState()
    ScrollingLine(book.title, LightTextVariant.Heading)
    val second = message ?: (if (loaded.hasChapters) chapters.getOrNull(chapter)?.title else null) ?: book.author
    ScrollingLine(second, LightTextVariant.Copy, lighten = message == null)
    if (loaded.hasChapters && chapter >= 0) {
        CenteredLine("${book.author} · Chapter ${chapter + 1} of ${chapters.size}", LightTextVariant.Detail, lighten = true)
    }
}

/** "Streaming · MP3 · 128 kbps · 44.1 kHz" in small text. */
@Composable
private fun StreamingLine() {
    val format by PlaybackHub.format.collectAsState()
    val duration by PlaybackHub.durationMs.collectAsState()
    val quality = formatLine(format, fileName = "", fileBytes = 0, durationMs = duration)
    CenteredLine(listOfNotNull("Streaming", quality).joinToString(" · "), LightTextVariant.Superfine, lighten = true)
}

/** The seek bar covers the whole episode. */
@Composable
private fun EpisodeSeekBar() {
    val position by PlaybackHub.positionMs.collectAsState()
    val duration by PlaybackHub.durationMs.collectAsState()
    SeekBarWithTimes(position = position, duration = duration, onSeek = PlaybackHub::seekTo)
}

@Composable
private fun Controls(hasChapters: Boolean) {
    val playing by PlaybackHub.isPlaying.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.5f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasChapters) {
            ControlButton(LightIcons.REWIND, "Previous chapter", onClick = PlaybackHub::previousChapter)
        } else {
            Box(modifier = Modifier.size(4.5f.gridUnitsAsDp()))
        }
        TextControlButton("−15", "Back 15 seconds") { PlaybackHub.skipBook(-BACK_MS) }
        ControlButton(
            if (playing) LightIcons.PAUSE else LightIcons.PLAY,
            if (playing) "Pause" else "Play",
            iconSize = 3f,
            onClick = PlaybackHub::togglePlayPause,
        )
        TextControlButton("+30", "Forward 30 seconds") { PlaybackHub.skipBook(FORWARD_MS) }
        if (hasChapters) {
            ControlButton(LightIcons.FAST_FORWARD, "Next chapter", onClick = PlaybackHub::nextChapter)
        } else {
            Box(modifier = Modifier.size(4.5f.gridUnitsAsDp()))
        }
    }
}

private const val BACK_MS = 15_000L
private const val FORWARD_MS = 30_000L

/** A control with a few characters instead of an icon ("−15", "+30"), the size of the others. */
@Composable
private fun TextControlButton(text: String, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(4.5f.gridUnitsAsDp())
            .lightClickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = text, variant = LightTextVariant.Subheading)
    }
}

@Composable
private fun FramedTextButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(3.5f.gridUnitsAsDp())
            .border(1.dp, LightThemeTokens.colors.contentSecondary)
            .lightClickable(onClickLabel = text, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = text, variant = LightTextVariant.Detail, maxLines = 1)
    }
}
