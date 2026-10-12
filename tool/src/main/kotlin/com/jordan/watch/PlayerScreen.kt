package com.jordan.watch

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.jordan.watch.logic.Aspect
import com.jordan.watch.logic.AspectMode
import com.jordan.watch.logic.Cue
import com.jordan.watch.logic.NextUp
import com.jordan.watch.logic.NextUpCountdown
import com.jordan.watch.logic.PlayerMath
import com.jordan.watch.logic.Resume
import com.jordan.watch.logic.SleepTimer
import com.jordan.watch.logic.Speed
import com.jordan.watch.logic.Subtitles
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Which panel is open over the player (only one at a time). */
enum class PlayerPanel { NONE, SPEED, AUDIO, SUBTITLES, SLEEP, DETAILS }

/** Off, a track inside the video file, or a sidecar .srt (by its place in the list). */
sealed interface SubtitleChoice {
    data object Off : SubtitleChoice
    data class Embedded(val trackId: String) : SubtitleChoice
    data class Sidecar(val index: Int) : SubtitleChoice
}

/** A sidecar .srt file, already read. */
data class SidecarTrack(val name: String, val cues: List<Cue>)

/**
 * Full-screen player for one movie or episode, and the next ones after it. The app stays
 * portrait; with [FeatureFlags.ROTATED_PLAYER] on, the rotate button turns the whole player
 * (picture, controls and touches) 90 degrees without stopping it. Position and speed are
 * saved every 10 s while playing and at once on pause, seek, speed change and leaving.
 * The panels (speed, audio, subtitles, sleep, details) are in PlayerPanels.kt.
 */
class PlayerScreen(sealedActivity: SealedLightActivity, startId: String) : VolumeKeyScreen<Unit>(sealedActivity) {

    internal val player = DefaultLightVideo(sealedActivity).newPlayer()
    internal val playback = WatchLibrary.repositories.playback
    internal val settings = WatchLibrary.repositories.settings
    internal var id by mutableStateOf(startId)
    private var started = false
    private var cantPlay by mutableStateOf(false)
    private var turns by mutableIntStateOf(0)
    private var controlsShown by mutableStateOf(true)
    private var lastTouchMs by mutableLongStateOf(0L)
    internal var panel by mutableStateOf(PlayerPanel.NONE)
    private var dragFraction by mutableStateOf<Float?>(null)
    private var locked by mutableStateOf(false)
    private var lockHintShown by mutableStateOf(false)
    private var aspect by mutableStateOf(AspectMode.FIT)
    private var pinch by mutableFloatStateOf(1f)
    internal var subtitleChoice by mutableStateOf<SubtitleChoice>(SubtitleChoice.Off)
    internal var subtitleOffsetMs by mutableLongStateOf(0L)
    internal val sidecars = MutableStateFlow<List<SidecarTrack>>(emptyList())
    internal var sleepChoice by mutableStateOf<Int?>(null)
    internal var sleepEndsAt by mutableStateOf<Long?>(null)
    internal var nowMs by mutableLongStateOf(System.currentTimeMillis())
    private var nextUpStartedAt by mutableStateOf<Long?>(null)
    internal var fileBytes = 0L

    override fun willShow() {
        if (started) return
        started = true
        val s = settings.settings.value
        turns = PlayerMath.startTurns(s.startIn, s.lastOrientation, FeatureFlags.ROTATED_PLAYER)
        loadAspect()
        openCurrent()
    }

    override fun willHide() = saveAndPause()

    override fun onAppPause() = saveAndPause()

    override fun onScreenDestroy() {
        save()
        player.release()
    }

    /** Headset and Bluetooth buttons: play/pause, and next/previous skip. Volume keys as everywhere. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> togglePlay()
            KeyEvent.KEYCODE_MEDIA_PLAY -> player.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> saveAndPause()
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> skipBy(1)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> skipBy(-1)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
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
        Clock()
        AutoHide(isPlaying)
        LightTheme(colors = LightThemeColors.Dark) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Box(Modifier.fillMaxSize().quarterTurned(turns), contentAlignment = Alignment.Center) {
                    Picture()
                    SubtitleLayer()
                    if (locked) LockLayer() else TouchLayer()
                    AnimatedVisibility(visible = controlsShown && !locked, enter = fadeIn(), exit = fadeOut()) {
                        Controls(isPlaying, ended)
                    }
                    if (nextUpStartedAt != null) NextUpOverlay()
                }
            }
        }
    }

    @Composable
    private fun AutoHide(isPlaying: Boolean) {
        LaunchedEffect(controlsShown, lastTouchMs, isPlaying, panel, dragFraction) {
            if (controlsShown && isPlaying && panel == PlayerPanel.NONE && dragFraction == null) {
                delay(PlayerMath.CONTROLS_HIDE_MS)
                controlsShown = false
            }
        }
        LaunchedEffect(lockHintShown, lastTouchMs) {
            if (lockHintShown) {
                delay(PlayerMath.CONTROLS_HIDE_MS)
                lockHintShown = false
            }
        }
    }

    /** Ticks once a second for the sleep timer and the next-up countdown. */
    @Composable
    private fun Clock() {
        LaunchedEffect(Unit) {
            while (true) {
                nowMs = System.currentTimeMillis()
                if (SleepTimer.isDue(sleepEndsAt, nowMs)) sleepNow()
                val started = nextUpStartedAt
                if (started != null && NextUpCountdown.secondsLeft(started, nowMs) == 0) playNext()
                delay(CLOCK_TICK_MS)
            }
        }
    }

    /** The video, sized by Fit / Fill / Zoom and the pinch, cropped to the screen. */
    @Composable
    private fun Picture() {
        val (w, h) = player.videoSize.collectAsState().value
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            val (pw, ph) = Aspect.pictureSize(boxW, boxH, w, h, aspect, pinch)
            val modifier = with(LocalDensity.current) { Modifier.requiredSize(pw.toDp(), ph.toDp()) }
            player.Surface(modifier)
        }
    }

    /** Tap shows or hides the controls; double tap on the left/right half skips; pinch zooms. */
    @Composable
    private fun TouchLayer() {
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            val show = !controlsShown
                            panel = PlayerPanel.NONE
                            poke()
                            controlsShown = show
                        },
                        onDoubleTap = { offset ->
                            skipBy(PlayerMath.doubleTapDirection(offset.x, size.width.toFloat()))
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) pinch = Aspect.clampPinch(pinch * zoom) }
                },
        )
        LaunchedEffect(pinch) {
            delay(PINCH_SAVE_DELAY_MS)
            saveAspect()
        }
    }

    /** While locked, touches do nothing except show the Unlock button for 3 s. */
    @Composable
    private fun LockLayer() {
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onTap = {
                    lockHintShown = true
                    lastTouchMs = System.currentTimeMillis()
                })
            },
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (lockHintShown) {
                Column(
                    Modifier.padding(bottom = 3f.gridUnitsAsDp()).background(Color.Black.copy(alpha = 0.7f)).padding(1f.gridUnitsAsDp()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LightText(text = "Controls are locked", variant = LightTextVariant.Detail, lighten = true)
                    PlayerTextButton("Unlock", underline = true) {
                        locked = false
                        lockHintShown = false
                        poke()
                    }
                }
            }
        }
    }

    @Composable
    private fun Controls(isPlaying: Boolean, ended: Boolean) {
        Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))) {
            TopRow()
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (panel == PlayerPanel.NONE) CenterButtons(isPlaying, ended) else PanelArea()
            }
            SeekRow()
            ButtonRow(
                "Speed ${Speed.label(player.speed.collectAsState().value)}" to PlayerPanel.SPEED,
                "Audio" to PlayerPanel.AUDIO,
                "Subtitles" to PlayerPanel.SUBTITLES,
            )
            BottomRow()
            Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
        }
    }

    @Composable
    private fun TopRow() {
        val title = remember(id) { titleText() }
        Row(Modifier.fillMaxWidth().padding(horizontal = 0.5f.gridUnitsAsDp()), verticalAlignment = Alignment.CenterVertically) {
            IconButton(LightIcons.BACK) { goBack() }
            OneLine(title, LightTextVariant.Detail, Modifier.weight(1f))
            PlayerTextButton("Details", underline = panel == PlayerPanel.DETAILS) { togglePanel(PlayerPanel.DETAILS) }
            if (FeatureFlags.ROTATED_PLAYER) IconButton(LightIcons.ROTATE, onClick = ::rotate)
        }
    }

    @Composable
    private fun CenterButtons(isPlaying: Boolean, ended: Boolean) {
        val (previous, next) = remember(id) { neighbours() }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2f.gridUnitsAsDp())) {
                SkipButton(-1)
                IconButton(if (isPlaying) LightIcons.PAUSE else LightIcons.PLAY, size = 4f) {
                    if (ended) restart() else togglePlay()
                }
                SkipButton(1)
            }
            Spacer(Modifier.height(1f.gridUnitsAsDp()))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.5f.gridUnitsAsDp())) {
                PlayerTextButton("‹ Previous", enabled = previous != null) { previous?.let(::playId) }
                PlayerTextButton("+90s") { skipAhead() }
                PlayerTextButton("Next ›", enabled = next != null) { next?.let(::playId) }
            }
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

    /** A row of panel buttons; the open panel's button is underlined. */
    @Composable
    private fun ButtonRow(vararg buttons: Pair<String, PlayerPanel>) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()), horizontalArrangement = Arrangement.SpaceBetween) {
            buttons.forEach { (label, which) -> PlayerTextButton(label, underline = panel == which) { togglePanel(which) } }
        }
    }

    @Composable
    private fun BottomRow() {
        Row(Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()), horizontalArrangement = Arrangement.SpaceBetween) {
            PlayerTextButton(Aspect.label(aspect)) { changeAspect(Aspect.next(aspect)) }
            PlayerTextButton(SleepTimer.buttonText(sleepChoice, sleepEndsAt, nowMs), underline = panel == PlayerPanel.SLEEP) {
                togglePanel(PlayerPanel.SLEEP)
            }
            PlayerTextButton("Lock") {
                locked = true
                panel = PlayerPanel.NONE
                lockHintShown = true
                lastTouchMs = System.currentTimeMillis()
            }
        }
    }

    /** "Up next" with a 10-second countdown; Cancel stays on this one. */
    @Composable
    private fun NextUpOverlay() {
        val started = nextUpStartedAt ?: return
        val next = remember(id) { neighbours().second } ?: return
        Column(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).padding(2f.gridUnitsAsDp()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LightText(text = "Up next", variant = LightTextVariant.Detail, lighten = true)
            OneLine(titleText(next), LightTextVariant.Subheading)
            Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
            LightText(text = "Starts in ${NextUpCountdown.secondsLeft(started, nowMs)} s", variant = LightTextVariant.Copy)
            Spacer(Modifier.height(1.5f.gridUnitsAsDp()))
            Row(horizontalArrangement = Arrangement.spacedBy(2f.gridUnitsAsDp())) {
                PlayerTextButton("Play now", underline = true) { playNext() }
                PlayerTextButton("Cancel", underline = true) {
                    nextUpStartedAt = null
                    poke()
                }
            }
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

    /** Saves every 10 s while playing, at once when it pauses; at the end marks it watched. */
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
        LaunchedEffect(ended, id) {
            if (ended) onEnded()
        }
    }

    private fun onEnded() {
        save()
        val finished = id
        WatchLibrary.inBackground { playback.markWatched(finished, System.currentTimeMillis()) }
        val wantsNext = NextUpCountdown.shouldOffer(neighbours().second != null, sleepChoice)
        if (sleepChoice == SleepTimer.END_OF_TITLE) setSleep(null)
        if (wantsNext) nextUpStartedAt = System.currentTimeMillis()
    }

    /** Opens [id]'s file at its saved place. Anything wrong shows "Can't play this file". */
    private fun openCurrent() {
        val found = videoFile()
        if (found == null || !found.first.isFile) {
            cantPlay = true
            return
        }
        val (file, runtimeMin) = found
        fileBytes = file.length()
        val saved = playback.position(id)
        val start = if (playback.isWatched(id)) 0L else Resume.startPosition(saved?.positionMs ?: 0, (runtimeMin ?: 0) * 60_000L)
        try {
            player.setSpeed(Speed.snap(saved?.speed ?: 1f))
            player.setSource(file, start)
            player.selectSubtitleTrack(null)
            player.play()
        } catch (e: Exception) {
            cantPlay = true
            return
        }
        loadSidecars(file)
    }

    /** Reads the .srt files next to the video, off the main thread. A broken one is skipped. */
    private fun loadSidecars(video: File) {
        sidecars.value = emptyList()
        val forId = id
        WatchLibrary.inBackground {
            val names = video.parentFile?.list()?.toList().orEmpty()
            val tracks = Subtitles.findSidecars(video.name, names).mapNotNull { side ->
                val cues = runCatching { Subtitles.parseSrt(File(video.parentFile, side.fileName).readText()) }.getOrNull()
                cues?.takeIf { it.isNotEmpty() }?.let { SidecarTrack(Subtitles.languageName(side.language) ?: "Subtitles", it) }
            }
            if (forId == id) sidecars.value = tracks
        }
    }

    /** Switches to another title in the same player: saves this one, resets per-title choices. */
    internal fun playId(nextId: String) {
        save()
        nextUpStartedAt = null
        subtitleChoice = SubtitleChoice.Off
        subtitleOffsetMs = 0L
        panel = PlayerPanel.NONE
        id = nextId
        openCurrent()
        poke()
    }

    private fun playNext() {
        nextUpStartedAt = null
        neighbours().second?.let(::playId)
    }

    private fun restart() {
        nextUpStartedAt = null
        player.seekTo(0)
        player.play()
    }

    internal fun togglePlay() {
        if (player.isPlaying.value) saveAndPause() else player.play()
    }

    internal fun togglePanel(which: PlayerPanel) {
        panel = if (panel == which) PlayerPanel.NONE else which
    }

    private fun skipBy(direction: Int) {
        val seconds = PlayerMath.skipSeconds(settings.settings.value.skipSeconds)
        player.seekTo(PlayerMath.skip(player.positionMs.value, direction * seconds * 1000L, player.durationMs.value))
        save()
    }

    private fun skipAhead() {
        player.seekTo(PlayerMath.skip(player.positionMs.value, PlayerMath.SKIP_AHEAD_MS, player.durationMs.value))
        save()
    }

    internal fun changeSpeed(speed: Float) {
        player.setSpeed(Speed.snap(speed))
        save()
    }

    internal fun setSleep(choice: Int?) {
        sleepChoice = choice
        sleepEndsAt = SleepTimer.endsAt(choice, System.currentTimeMillis())
    }

    private fun sleepNow() {
        setSleep(null)
        saveAndPause()
        controlsShown = true
    }

    private fun changeAspect(mode: AspectMode) {
        aspect = mode
        pinch = 1f
        saveAspect()
    }

    private fun orientation() = PlayerMath.orientationName(turns)

    private fun loadAspect() {
        val s = settings.settings.value
        aspect = Aspect.fromName(s.aspects[orientation()])
        pinch = Aspect.clampPinch(s.pinches[orientation()] ?: 1f)
    }

    private fun saveAspect() {
        val key = orientation()
        val mode = aspect.name
        val zoom = pinch
        WatchLibrary.inBackground { settings.update { it.copy(aspects = it.aspects + (key to mode), pinches = it.pinches + (key to zoom)) } }
    }

    private fun rotate() {
        turns = (turns + 1) % 2
        val name = PlayerMath.orientationName(turns)
        loadAspect()
        WatchLibrary.inBackground { settings.update { it.copy(lastOrientation = name) } }
    }

    /** Any touch keeps the controls up for another 3 s. */
    internal fun poke() {
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
        val forId = id
        val position = player.positionMs.value
        val duration = player.durationMs.value
        if (duration <= 0) return
        val speed = Speed.snap(player.speed.value)
        WatchLibrary.inBackground { playback.savePosition(forId, position, duration, speed, System.currentTimeMillis()) }
    }

    /** The video file and the json runtime (for the 5 s rewind on reopen), or null. */
    private fun videoFile(): Pair<File, Int?>? {
        val library = WatchLibrary.state.value.library
        library.movie(id)?.let { return File(WatchPaths.root, "${it.folder}/${it.video}") to it.movie.runtimeMin }
        library.episode(id)?.let { (_, e) -> return File(WatchPaths.root, "${e.folder}/${e.video}") to e.episode.runtimeMin }
        return null
    }

    /** The previous and next film in the collection, or episode in the show (ids, or null). */
    private fun neighbours(): Pair<String?, String?> {
        val library = WatchLibrary.state.value.library
        library.movie(id)?.let { entry ->
            val all = library.movies.map { it.movie }
            return NextUp.previousMovie(entry.movie, all)?.id to NextUp.nextMovie(entry.movie, all)?.id
        }
        library.episode(id)?.let { (show, entry) ->
            val all = show.episodes.map { it.episode }
            return NextUp.previousEpisode(entry.episode, all)?.id to NextUp.nextEpisode(entry.episode, all)?.id
        }
        return null to null
    }

    private fun titleText(forId: String = id): String {
        val library = WatchLibrary.state.value.library
        library.movie(forId)?.let { return it.movie.title }
        library.episode(forId)?.let { (show, e) -> return "${show.show.title} · S${e.episode.season} E${e.episode.episode} · ${e.episode.title}" }
        return ""
    }

    private companion object {
        const val SEEK_SETTLE_MS = 300L
        const val CLOCK_TICK_MS = 1_000L
        const val PINCH_SAVE_DELAY_MS = 600L
    }
}
