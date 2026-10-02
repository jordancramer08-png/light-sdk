package com.thelightphone.listen.playback

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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.artwork.ArtImage
import com.thelightphone.listen.artwork.ArtSize
import com.thelightphone.listen.artwork.artLetter
import com.thelightphone.listen.books.Book
import com.thelightphone.listen.books.BookPosition
import com.thelightphone.listen.books.ChaptersScreen
import com.thelightphone.listen.artwork.artSource
import com.thelightphone.listen.books.bookPositionMs
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
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Audiobook Now Playing: cover, book title and chapter, a seek bar for the chapter with the
 * whole-book place under it, previous chapter / back 15 s / play-pause / forward 30 s / next
 * chapter, and the speed and sleep timer. The list icon at the top opens the chapters.
 */
class BookNowPlayingScreen(sealedActivity: SealedLightActivity) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val book by PlaybackHub.book.collectAsState()
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Now Playing"),
                rightButton = book?.let {
                    LightBarButton.LightIcon(icon = LightIcons.LIST, onClick = ::openChapters, contentDescription = "Chapters")
                },
            )
            val current = book
            if (current == null) {
                CenteredMessage("No audiobook is playing.\n\nPick one in Audiobooks.", modifier = Modifier.weight(1f))
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    ArtImage(
                        source = current.artSource(),
                        size = ArtSize.LARGE,
                        letter = artLetter(current.title),
                        modifier = Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true),
                        placeholder = { letter, modifier -> FramedPlaceholder(letter, modifier) },
                    )
                }
                Column(modifier = Modifier.padding(horizontal = 1.5f.gridUnitsAsDp())) {
                    TitleLines(current)
                    ChapterSeekBar()
                    BookPlace(current)
                    BookFileQuality(current)
                    BookControls()
                    SpeedAndSleep(onSpeed = ::openSpeed, onSleep = ::openSleep)
                }
            }
        }
    }

    private fun openChapters() {
        navigateTo(screenFactory = { ChaptersScreen(it) })
    }

    private fun openSpeed() {
        val speeds = PlaybackHub.SPEEDS
        navigateTo(
            screenFactory = {
                ChoiceScreen(it, "Speed", "Playback speed", speeds.map(::speedText), speeds.indexOf(PlaybackHub.speed.value))
            },
            resultCallback = { i -> speeds.getOrNull(i)?.let(PlaybackHub::setSpeed) },
        )
    }

    private fun openSleep() {
        val minutes = PlaybackHub.SLEEP_MINUTES
        val choices = listOf("Off") + minutes.map { "$it minutes" } + "End of chapter"
        val current = when (PlaybackHub.sleep.value) {
            null -> 0
            SleepTimer.EndOfChapter -> choices.lastIndex
            // A running countdown isn't one of the choices any more.
            is SleepTimer.At -> -1
        }
        navigateTo(
            screenFactory = { ChoiceScreen(it, "Sleep timer", "Pause playback after", choices, current) },
            resultCallback = { i ->
                when (i) {
                    0 -> PlaybackHub.cancelSleepTimer()
                    choices.lastIndex -> PlaybackHub.startSleepTimer(null)
                    else -> minutes.getOrNull(i - 1)?.let(PlaybackHub::startSleepTimer)
                }
            },
        )
    }
}

/** The book's title, then the chapter (or "Can't play this file"). */
@Composable
private fun TitleLines(book: Book) {
    val chapters by PlaybackHub.chapters.collectAsState()
    val chapter by PlaybackHub.chapter.collectAsState()
    val message by PlaybackHub.message.collectAsState()
    ScrollingLine(book.title, LightTextVariant.Heading)
    ScrollingLine(message ?: chapters.getOrNull(chapter)?.title ?: book.author, LightTextVariant.Copy, lighten = message == null)
}

/** The seek bar covers the current chapter only; its times are inside the chapter. */
@Composable
private fun ChapterSeekBar() {
    val chapters by PlaybackHub.chapters.collectAsState()
    val index by PlaybackHub.chapter.collectAsState()
    val position by PlaybackHub.positionMs.collectAsState()
    val fileDuration by PlaybackHub.durationMs.collectAsState()
    val chapter = chapters.getOrNull(index)
    val start = chapter?.startMs ?: 0
    val end = chapter?.endMs ?: fileDuration
    SeekBarWithTimes(
        position = (position - start).coerceAtLeast(0),
        duration = (end - start).coerceAtLeast(0),
        onSeek = { PlaybackHub.seekTo(start + it) },
    )
}

/** "Chapter 12 of 40 · 3:12:05 of 11:40:00" — where the book is as a whole. */
@Composable
private fun BookPlace(book: Book) {
    val chapters by PlaybackHub.chapters.collectAsState()
    val chapter by PlaybackHub.chapter.collectAsState()
    val index by PlaybackHub.index.collectAsState()
    val position by PlaybackHub.positionMs.collectAsState()
    val parts = buildList {
        if (chapter >= 0) add("Chapter ${chapter + 1} of ${chapters.size}")
        if (book.durationMs > 0) {
            val here = bookPositionMs(book, BookPosition(fileIndex = index.coerceAtLeast(0), positionMs = position))
            add("${formatTime(here)} of ${formatTime(book.durationMs)}")
        }
    }
    CenteredLine(parts.joinToString(" · "), LightTextVariant.Detail, lighten = true)
}

/** The quality line for the book file playing now. */
@Composable
private fun BookFileQuality(book: Book) {
    val index by PlaybackHub.index.collectAsState()
    val file = book.files.getOrNull(index)
    QualityLine(fileName = file?.path ?: "", fileBytes = file?.size ?: 0, durationMs = file?.durationMs ?: 0)
}

@Composable
private fun BookControls() {
    val playing by PlaybackHub.isPlaying.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.5f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlButton(LightIcons.REWIND, "Previous chapter", onClick = PlaybackHub::previousChapter)
        TextControlButton("−15", "Back 15 seconds") { PlaybackHub.skipBook(-BACK_MS) }
        ControlButton(
            if (playing) LightIcons.PAUSE else LightIcons.PLAY,
            if (playing) "Pause" else "Play",
            iconSize = 3f,
            onClick = PlaybackHub::togglePlayPause,
        )
        TextControlButton("+30", "Forward 30 seconds") { PlaybackHub.skipBook(FORWARD_MS) }
        ControlButton(LightIcons.FAST_FORWARD, "Next chapter", onClick = PlaybackHub::nextChapter)
    }
}

private const val BACK_MS = 15_000L
private const val FORWARD_MS = 30_000L

/** A control with a few characters instead of an icon ("−15", "+30"), same size as the others. */
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

/** Two framed buttons: the speed ("1.25×") and the sleep timer ("Sleep 14:32"). */
@Composable
private fun SpeedAndSleep(onSpeed: () -> Unit, onSleep: () -> Unit) {
    val speed by PlaybackHub.speed.collectAsState()
    val sleep by PlaybackHub.sleep.collectAsState()
    val sleepLabel by produceState("Sleep", sleep) {
        when (val timer = sleep) {
            null -> value = "Sleep"
            SleepTimer.EndOfChapter -> value = "Sleep: chapter end"
            is SleepTimer.At -> while (true) {
                value = "Sleep ${formatTime(timer.endsAt - System.currentTimeMillis() + 999)}"
                delay(1_000)
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 1f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
    ) {
        FramedTextButton("${speedText(speed)} speed", Modifier.weight(1f), onSpeed)
        FramedTextButton(sleepLabel, Modifier.weight(1f), onSleep)
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

/** 1f → "1.0×", 1.25f → "1.25×", 0.75f → "0.75×". */
fun speedText(speed: Float): String {
    val text = String.format(Locale.US, "%.2f", speed).trimEnd('0')
    return (if (text.endsWith('.')) text + "0" else text) + "×"
}
