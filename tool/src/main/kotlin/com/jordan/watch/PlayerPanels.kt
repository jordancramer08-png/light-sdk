package com.jordan.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.jordan.watch.logic.DelayedText
import com.jordan.watch.logic.SleepTimer
import com.jordan.watch.logic.Speed
import com.jordan.watch.logic.Subtitles
import com.jordan.watch.logic.TechDetails
import com.jordan.watch.storage.WatchLibrary
import com.jordan.watch.ui.ChoiceRow
import com.jordan.watch.ui.HairlineDivider
import com.jordan.watch.ui.ListStepperRow
import com.jordan.watch.ui.SectionHeading
import com.jordan.watch.ui.StepperRow
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** The open panel, scrolling if it's taller than the space between the top row and the seek bar. */
@Composable
internal fun PlayerScreen.PanelArea() {
    LightScrollView(modifier = Modifier.fillMaxSize().padding(horizontal = 1f.gridUnitsAsDp())) {
        when (panel) {
            PlayerPanel.SPEED -> SpeedPanel()
            PlayerPanel.AUDIO -> AudioPanel()
            PlayerPanel.SUBTITLES -> SubtitlePanel()
            PlayerPanel.SLEEP -> SleepPanel()
            PlayerPanel.DETAILS -> DetailsPanel()
            PlayerPanel.NONE -> Unit
        }
        Spacer(Modifier.height(1f.gridUnitsAsDp()))
    }
}

/** − and + in 0.25 steps, and the quick speeds. The chosen one is underlined. */
@Composable
private fun PlayerScreen.SpeedPanel() {
    val speed = Speed.snap(player.speed.collectAsState().value)
    SectionHeading("Speed")
    StepperRow(
        label = "Speed",
        value = Speed.label(speed),
        onPrevious = { changeSpeed(Speed.slower(speed)); poke() },
        onNext = { changeSpeed(Speed.faster(speed)); poke() },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Speed.quick.forEach { q -> PlayerTextButton(Speed.label(q), underline = Speed.snap(q) == speed) { changeSpeed(q); poke() } }
    }
}

@Composable
private fun PlayerScreen.AudioPanel() {
    val tracks by player.audioTracks.collectAsState()
    SectionHeading("Audio track")
    if (tracks.isEmpty()) {
        LightText(text = "No audio tracks found.", variant = LightTextVariant.Copy, lighten = true)
        return
    }
    tracks.forEachIndexed { i, t ->
        val codec = TechDetails.codecName(t.codec)?.let { " · $it" } ?: ""
        ChoiceRow(Subtitles.trackName(t.label, t.language, i + 1) + codec, t.isSelected) {
            player.selectAudioTrack(t.id)
            poke()
        }
        if (i != tracks.lastIndex) HairlineDivider()
    }
}

/** Off, tracks inside the file, .srt files; then sync, size and position. */
@Composable
private fun PlayerScreen.SubtitlePanel() {
    val embedded by player.subtitleTracks.collectAsState()
    val files by sidecars.collectAsState()
    val s by settings.settings.collectAsState()
    SectionHeading("Subtitles")
    ChoiceRow("Off", subtitleChoice == SubtitleChoice.Off) { chooseSubtitles(SubtitleChoice.Off) }
    embedded.forEachIndexed { i, t ->
        HairlineDivider()
        val choice = SubtitleChoice.Embedded(t.id)
        ChoiceRow("${Subtitles.trackName(t.label, t.language, i + 1)} (in file)", subtitleChoice == choice) { chooseSubtitles(choice) }
    }
    files.forEachIndexed { i, f ->
        HairlineDivider()
        val choice = SubtitleChoice.Sidecar(i)
        ChoiceRow("${f.name} (.srt)", subtitleChoice == choice) { chooseSubtitles(choice) }
    }
    SectionHeading("Sync")
    StepperRow(
        label = "Timing",
        value = Subtitles.nudgeLabel(subtitleOffsetMs),
        onPrevious = { subtitleOffsetMs = Subtitles.nudge(subtitleOffsetMs, -1); poke() },
        onNext = { subtitleOffsetMs = Subtitles.nudge(subtitleOffsetMs, 1); poke() },
    )
    if (subtitleChoice is SubtitleChoice.Embedded) {
        LightText(text = "Subtitles inside the file can only be moved later.", variant = LightTextVariant.Fine, lighten = true)
    }
    SectionHeading("Look")
    ListStepperRow("Size", SUBTITLE_SIZES, s.subtitleSize.takeIf { it in SUBTITLE_SIZES } ?: "MEDIUM", ::subtitleSizeName) { z ->
        WatchLibrary.inBackground { settings.update { it.copy(subtitleSize = z) } }
        poke()
    }
    ListStepperRow("Position", SUBTITLE_POSITIONS, s.subtitlePosition.takeIf { it in SUBTITLE_POSITIONS } ?: "BOTTOM", ::subtitlePositionName) { p ->
        WatchLibrary.inBackground { settings.update { it.copy(subtitlePosition = p) } }
        poke()
    }
}

@Composable
private fun PlayerScreen.SleepPanel() {
    SectionHeading("Sleep timer")
    val choices = listOf<Int?>(null) + SleepTimer.choices + SleepTimer.END_OF_TITLE
    choices.forEachIndexed { i, c ->
        ChoiceRow(SleepTimer.label(c), sleepChoice == c) {
            setSleep(c)
            poke()
        }
        if (i != choices.lastIndex) HairlineDivider()
    }
}

/** Codec, resolution and bitrate of what's playing. */
@Composable
private fun PlayerScreen.DetailsPanel() {
    val format by player.videoFormat.collectAsState()
    val size by player.videoSize.collectAsState()
    val duration by player.durationMs.collectAsState()
    val audio = player.audioTracks.collectAsState().value.firstOrNull { it.isSelected }
    val lines = TechDetails.lines(
        videoCodec = format?.codec,
        width = format?.width?.takeIf { it > 0 } ?: size.first,
        height = format?.height?.takeIf { it > 0 } ?: size.second,
        videoBitrate = format?.bitrate,
        frameRate = format?.frameRate,
        audioCodec = audio?.codec,
        fileBytes = fileBytes,
        durationMs = duration,
    )
    SectionHeading("Details")
    if (lines.isEmpty()) LightText(text = "Not known yet.", variant = LightTextVariant.Detail, lighten = true)
    lines.forEach { LightText(text = it, variant = LightTextVariant.Detail) }
}

private fun PlayerScreen.chooseSubtitles(choice: SubtitleChoice) {
    subtitleChoice = choice
    player.selectSubtitleTrack((choice as? SubtitleChoice.Embedded)?.trackId)
    poke()
}

/**
 * The subtitle lines over the picture, white on a dark box, at the chosen size and height.
 * .srt files are timed here (so they can move earlier or later); tracks inside the file come
 * from the player and can only be held back.
 */
@Composable
internal fun PlayerScreen.SubtitleLayer() {
    val position by player.positionMs.collectAsState()
    val fromPlayer by player.subtitleText.collectAsState()
    val files by sidecars.collectAsState()
    val s by settings.settings.collectAsState()
    val delayed = remember(id) { DelayedText() }
    var embeddedShown by remember(id) { mutableStateOf("") }
    LaunchedEffect(position, fromPlayer, subtitleOffsetMs) {
        delayed.record(position, fromPlayer)
        embeddedShown = delayed.textAt(position, subtitleOffsetMs)
    }
    val text = when (val c = subtitleChoice) {
        SubtitleChoice.Off -> ""
        is SubtitleChoice.Embedded -> embeddedShown
        is SubtitleChoice.Sidecar -> files.getOrNull(c.index)?.let { Subtitles.textAt(it.cues, position, subtitleOffsetMs) } ?: ""
    }
    if (text.isEmpty()) return
    val (align, gap) = when (s.subtitlePosition) {
        "TOP" -> Alignment.TopCenter to 3f
        "HIGHER" -> Alignment.BottomCenter to 9f
        else -> Alignment.BottomCenter to 2f
    }
    Box(Modifier.fillMaxSize().padding(horizontal = 1f.gridUnitsAsDp(), vertical = gap.gridUnitsAsDp()), contentAlignment = align) {
        LightText(
            text = text,
            variant = SUBTITLE_VARIANTS[Subtitles.sizeStep(s.subtitleSize)],
            align = TextAlign.Center,
            color = Color.White,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 0.5f.gridUnitsAsDp()),
        )
    }
}

/** A text button at least 48 dp tall. [enabled] = false draws it lighter and ignores taps. */
@Composable
internal fun PlayerScreen.PlayerTextButton(text: String, underline: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.height(3f.gridUnitsAsDp()).lightClickable(onClick = {
            if (enabled) onClick()
            poke()
        }).padding(horizontal = 0.5f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = text, variant = LightTextVariant.Detail, underline = underline, lighten = !enabled, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

internal fun subtitleSizeName(name: String) = when (name) {
    "SMALL" -> "Small"
    "LARGE" -> "Large"
    "XLARGE" -> "Extra large"
    else -> "Medium"
}

internal fun subtitlePositionName(name: String) = when (name) {
    "TOP" -> "Top"
    "HIGHER" -> "Raised"
    else -> "Bottom"
}

internal val SUBTITLE_SIZES = listOf("SMALL", "MEDIUM", "LARGE", "XLARGE")
internal val SUBTITLE_POSITIONS = listOf("BOTTOM", "HIGHER", "TOP")
private val SUBTITLE_VARIANTS = listOf(LightTextVariant.Detail, LightTextVariant.Copy, LightTextVariant.Subheading, LightTextVariant.Heading)
