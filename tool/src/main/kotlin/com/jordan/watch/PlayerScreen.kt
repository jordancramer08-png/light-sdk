package com.jordan.watch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import com.jordan.watch.logic.PlayerMath
import com.jordan.watch.logic.Resume
import com.jordan.watch.logic.Speed
import com.jordan.watch.storage.WatchLibrary
import com.jordan.watch.storage.WatchPaths
import com.jordan.watch.ui.BackTopBar
import com.jordan.watch.ui.CenteredMessage
import com.jordan.watch.ui.OneLine
import com.jordan.watch.ui.ThemedScreen
import com.jordan.watch.ui.VolumeKeyScreen
import com.jordan.watch.ui.quarterTurned
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightTouchableProgressBar
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.sdk.video.DefaultLightVideo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Full-screen player for one movie or episode. The app stays portrait; with
 * [FeatureFlags.ROTATED_PLAYER] on, the rotate button turns the whole player (picture,
 * controls and touches) 90 degrees without stopping it. Position and speed are saved every
 * 10 s while playing and at once on pause, seek, speed change and leaving.
 */
class PlayerScreen(sealedActivity: SealedLightActivity, private val id: String) : VolumeKeyScreen<Unit>(sealedActivity) {

    private val player = DefaultLightVideo(sealedActivity).newPlayer()
    private val playback = WatchLibrary.repositories.playback
    private val settings = WatchLibrary.repositories.settings
    private var started = false
    private var cantPlay by mutableStateOf(false)
    private var turns by mutableIntStateOf(0)
    private var controlsShown by mutableStateOf(true)
    private var lastTouchMs by mutableLongStateOf(0L)
    private var speedPanel by mutableStateOf(false)
    private var dragFraction by mutableStateOf<Float?>(null)

    override fun willShow() {
        if (started) return
        started = true
        val s = settings.settings.value
        turns = PlayerMath.startTurns(s.startIn, s.lastOrientation, FeatureFlags.ROTATED_PLAYER)
        val found = videoFile()
        if (found == null || !found.first.isFile) {
            cantPlay = true
            return
        }
        val (file, runtimeMin) = found
        val saved = playback.position(id)
        val start = if (playback.isWatched(id)) 0L else Resume.startPosition(saved?.positionMs ?: 0, (runtimeMin ?: 0) * 60_000L)
        player.setSpeed(Speed.snap(saved?.speed ?: 1f))
        player.setSource(file, start)
        player.play()
    }

    override fun willHide() = saveAndPause()

    override fun onAppPause() = saveAndPause()

    override fun onScreenDestroy() {
        save()
        player.release()
    }

    @Composable
    override fun Content() {
        val error by player.error.collectAsState()
        if (cantPlay || error != null) {
            ThemedScreen {
                BackTopBar("", onBack = { goBack() })
                CenteredMessage("Can't play this file.", Modifier.weight(1f))
            }
            return
        }
        val isPlaying by player.isPlaying.collectAsState()
        val ended by player.ended.collectAsState()
        AutoSave(isPlaying, ended)
        LaunchedEffect(controlsShown, lastTouchMs, isPlaying, speedPanel, dragFraction) {
            if (controlsShown && isPlaying && !speedPanel && dragFraction == null) {
                delay(PlayerMath.CONTROLS_HIDE_MS)
                controlsShown = false
            }
        }
        LightTheme(colors = LightThemeColors.Dark) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Box(Modifier.fillMaxSize().quarterTurned(turns), contentAlignment = Alignment.Center) {
                    Picture()
                    TouchLayer()
                    AnimatedVisibility(visible = controlsShown, enter = fadeIn(), exit = fadeOut()) {
                        Controls(isPlaying, ended)
                    }
                }
            }
        }
    }

    /** The video, as big as fits without cropping (aspect choices come in Session 7). */
    @Composable
    private fun Picture() {
        val (w, h) = player.videoSize.collectAsState().value
        val modifier = if (w > 0 && h > 0) Modifier.aspectRatio(w.toFloat() / h) else Modifier.fillMaxSize()
        player.Surface(modifier)
    }

    /** Tap shows or hides the controls; double tap on the left/right half skips. */
    @Composable
    private fun TouchLayer() {
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        val show = !controlsShown
                        speedPanel = false
                        poke()
                        controlsShown = show
                    },
                    onDoubleTap = { offset ->
                        skipBy(PlayerMath.doubleTapDirection(offset.x, size.width.toFloat()))
                    },
                )
            },
        )
    }

    @Composable
    private fun Controls(isPlaying: Boolean, ended: Boolean) {
        Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))) {
            TopRow()
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CenterButtons(isPlaying, ended)
            }
            if (speedPanel) SpeedPanel()
            SeekRow()
            BottomRow()
        }
    }

    @Composable
    private fun TopRow() {
        val title = remember(id) { titleText() }
        Row(Modifier.fillMaxWidth().padding(horizontal = 0.5f.gridUnitsAsDp()), verticalAlignment = Alignment.CenterVertically) {
            IconButton(LightIcons.BACK) { goBack() }
            OneLine(title, LightTextVariant.Detail, Modifier.weight(1f))
            if (FeatureFlags.ROTATED_PLAYER) IconButton(LightIcons.ROTATE, onClick = ::rotate)
        }
    }

    @Composable
    private fun CenterButtons(isPlaying: Boolean, ended: Boolean) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2f.gridUnitsAsDp())) {
            SkipButton(-1)
            IconButton(if (isPlaying) LightIcons.PAUSE else LightIcons.PLAY, size = 4f) {
                when {
                    isPlaying -> player.pause()
                    ended -> {
                        player.seekTo(0)
                        player.play()
                    }
                    else -> player.play()
                }
            }
            SkipButton(1)
        }
    }

    /** Tap skips back/forward by the setting; hold to scan, faster the longer it's held. */
    @Composable
    private fun SkipButton(direction: Int) {
        val scope = rememberCoroutineScope()
        val seconds = PlayerMath.skipSeconds(settings.settings.collectAsState().value.skipSeconds)
        val icon = if (direction < 0) LightIcons.SKIP_BACKWARD_FIFTEEN else LightIcons.SKIP_FORWARD_FIFTEEN
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(4.5f.gridUnitsAsDp()).pointerInput(direction) {
                    detectTapGestures(
                        onPress = {
                            var scanned = false
                            val job: Job = scope.launch {
                                delay(PlayerMath.SCAN_START_MS)
                                val began = System.currentTimeMillis()
                                while (isActive) {
                                    scanned = true
                                    poke()
                                    val step = PlayerMath.scanStepMs(System.currentTimeMillis() - began)
                                    player.seekTo(PlayerMath.skip(player.positionMs.value, direction * step, player.durationMs.value))
                                    delay(PlayerMath.SCAN_TICK_MS)
                                }
                            }
                            val released = tryAwaitRelease()
                            job.cancel()
                            poke()
                            if (scanned) save() else if (released) skipBy(direction)
                        },
                    )
                },
                contentAlignment = Alignment.Center,
            ) { LightIcon(icon, size = 3f) }
            LightText(text = "${seconds}s", variant = LightTextVariant.Fine, lighten = true)
        }
    }

    /** Elapsed, the seek bar, remaining. Dragging shows the time and seeks when the finger rests. */
    @Composable
    private fun SeekRow() {
        val position by player.positionMs.collectAsState()
        val duration by player.durationMs.collectAsState()
        val drag = dragFraction
        LaunchedEffect(drag) {
            if (drag == null) return@LaunchedEffect
            delay(SEEK_SETTLE_MS)
            player.seekTo(PlayerMath.fractionToMs(drag, duration))
            dragFraction = null
            save()
        }
        val shownMs = drag?.let { PlayerMath.fractionToMs(it, duration) } ?: position
        Column(Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
            if (drag != null) {
                LightText(
                    text = PlayerMath.timeLabel(shownMs),
                    variant = LightTextVariant.Subheading,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            LightTouchableProgressBar(
                colors = LightThemeColors.Dark,
                progress = if (duration > 0) shownMs.toFloat() / duration else 0f,
                onValueChange = {
                    dragFraction = it
                    poke()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth()) {
                LightText(text = PlayerMath.timeLabel(shownMs), variant = LightTextVariant.Fine)
                Spacer(Modifier.weight(1f))
                LightText(text = PlayerMath.remainingLabel(shownMs, duration), variant = LightTextVariant.Fine)
            }
        }
    }

    @Composable
    private fun BottomRow() {
        val speed by player.speed.collectAsState()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton("Speed ${Speed.label(speed)}", underline = speedPanel) { speedPanel = !speedPanel }
        }
    }

    /** − and + in 0.25 steps, and the quick speeds. The chosen one is underlined. */
    @Composable
    private fun SpeedPanel() {
        val speed = Speed.snap(player.speed.collectAsState().value)
        Column(Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton("−") { changeSpeed(Speed.slower(speed)) }
                LightText(
                    text = Speed.label(speed),
                    variant = LightTextVariant.Subheading,
                    modifier = Modifier.width(5f.gridUnitsAsDp()),
                )
                TextButton("+") { changeSpeed(Speed.faster(speed)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Speed.quick.forEach { q -> TextButton(Speed.label(q), underline = Speed.snap(q) == speed) { changeSpeed(q) } }
            }
            Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
        }
    }

    @Composable
    private fun IconButton(icon: LightIconConfiguration, size: Float = 2f, onClick: () -> Unit) {
        Box(
            Modifier.size((size + 1.5f).gridUnitsAsDp()).lightClickable(onClick = {
                onClick()
                poke()
            }),
            contentAlignment = Alignment.Center,
        ) { LightIcon(icon, size = size) }
    }

    @Composable
    private fun TextButton(text: String, underline: Boolean = false, onClick: () -> Unit) {
        Box(
            Modifier.height(3f.gridUnitsAsDp()).lightClickable(onClick = {
                onClick()
                poke()
            }).padding(horizontal = 0.5f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(text = text, variant = LightTextVariant.Detail, underline = underline, maxLines = 1, overflow = TextOverflow.Clip)
        }
    }

    /** Saves every 10 s while playing, at once when it pauses, and marks it watched at the end. */
    @Composable
    private fun AutoSave(isPlaying: Boolean, ended: Boolean) {
        LaunchedEffect(isPlaying) {
            if (!isPlaying) {
                save()
                controlsShown = true
                return@LaunchedEffect
            }
            while (true) {
                delay(Resume.SAVE_EVERY_MS)
                save()
            }
        }
        LaunchedEffect(ended) {
            if (ended) {
                save()
                WatchLibrary.inBackground { playback.markWatched(id, System.currentTimeMillis()) }
            }
        }
    }

    private fun skipBy(direction: Int) {
        val seconds = PlayerMath.skipSeconds(settings.settings.value.skipSeconds)
        player.seekTo(PlayerMath.skip(player.positionMs.value, direction * seconds * 1000L, player.durationMs.value))
        save()
    }

    private fun changeSpeed(speed: Float) {
        player.setSpeed(Speed.snap(speed))
        save()
    }

    private fun rotate() {
        turns = (turns + 1) % 2
        val name = PlayerMath.orientationName(turns)
        WatchLibrary.inBackground { settings.update { it.copy(lastOrientation = name) } }
    }

    /** Any touch keeps the controls up for another 3 s. */
    private fun poke() {
        controlsShown = true
        lastTouchMs = System.currentTimeMillis()
    }

    private fun saveAndPause() {
        save()
        player.pause()
    }

    /** Saves position and speed, once the player knows how long the file is. */
    private fun save() {
        if (!started || cantPlay) return
        val position = player.positionMs.value
        val duration = player.durationMs.value
        if (duration <= 0) return
        val speed = Speed.snap(player.speed.value)
        WatchLibrary.inBackground { playback.savePosition(id, position, duration, speed, System.currentTimeMillis()) }
    }

    /** The video file and the json runtime (for the 5 s rewind on reopen), or null. */
    private fun videoFile(): Pair<File, Int?>? {
        val library = WatchLibrary.state.value.library
        library.movie(id)?.let { return File(WatchPaths.root, "${it.folder}/${it.video}") to it.movie.runtimeMin }
        library.episode(id)?.let { (_, e) -> return File(WatchPaths.root, "${e.folder}/${e.video}") to e.episode.runtimeMin }
        return null
    }

    private fun titleText(): String {
        val library = WatchLibrary.state.value.library
        library.movie(id)?.let { return it.movie.title }
        library.episode(id)?.let { (show, e) -> return "${show.show.title} · S${e.episode.season} E${e.episode.episode} · ${e.episode.title}" }
        return ""
    }

    private companion object {
        const val SEEK_SETTLE_MS = 300L
    }
}
