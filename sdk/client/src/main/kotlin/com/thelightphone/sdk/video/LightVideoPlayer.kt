package com.thelightphone.sdk.video

import android.content.Context
import android.net.Uri
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.thelightphone.sdk.SealedLightActivity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Factory for a [LightVideoPlayer] without exposing an Android context. */
@JvmInline
value class DefaultLightVideo(private val sealedActivity: SealedLightActivity) {
    /** Creates a player. Call [LightVideoPlayer.release] when the owning screen is destroyed. */
    fun newPlayer(): LightVideoPlayer = LightVideoPlayer(sealedActivity.activity)
}

/** Kind of track in [LightVideoPlayer.audioTracks] / [LightVideoPlayer.subtitleTracks]. */
enum class LightTrackType { Audio, Subtitle }

/** One selectable audio or subtitle track. [id] is stable until the source changes. */
data class LightMediaTrack(
    val id: String,
    val type: LightTrackType,
    val label: String?,
    val language: String?,
    val codec: String?,
    val isSelected: Boolean,
)

/** What the video track is: size, codec and bitrate, for a details overlay. */
data class LightVideoFormat(
    val width: Int,
    val height: Int,
    val codec: String?,
    val bitrate: Int?,
    val frameRate: Float?,
)

/** A sidecar subtitle file (e.g. movie.en.srt) shown as a selectable track. */
data class LightSubtitleFile(val file: File, val language: String? = null, val label: String? = null)

/** Why playback failed; a tool shows "Can't play this file". */
data class LightVideoError(val code: Int, val message: String)

/**
 * Plays one local video file with ExoPlayer (hardware decoding, audio focus, pauses when
 * headphones unplug). Draw it with [Surface], which can be turned in 90° steps without
 * restarting playback.
 */
class LightVideoPlayer internal constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        setHandleAudioBecomingNoisy(true)
    }
    private val _positionMs = MutableStateFlow(0L)
    private val _durationMs = MutableStateFlow(0L)
    private val _isPlaying = MutableStateFlow(false)
    private val _ended = MutableStateFlow(false)
    private val _error = MutableStateFlow<LightVideoError?>(null)
    private val _speed = MutableStateFlow(1f)
    private val _videoSize = MutableStateFlow(0 to 0)
    private val _videoFormat = MutableStateFlow<LightVideoFormat?>(null)
    private val _audioTracks = MutableStateFlow<List<LightMediaTrack>>(emptyList())
    private val _subtitleTracks = MutableStateFlow<List<LightMediaTrack>>(emptyList())
    private val _subtitleText = MutableStateFlow("")
    private var positionJob: Job? = null
    private var textureView: TextureView? = null
    private var released = false

    /** Current position in ms, updated while playing. */
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    /** Duration in ms, or 0 while unknown. */
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    /** True once the file has played to its end. */
    val ended: StateFlow<Boolean> = _ended.asStateFlow()
    /** Playback failure, or null. */
    val error: StateFlow<LightVideoError?> = _error.asStateFlow()
    val speed: StateFlow<Float> = _speed.asStateFlow()
    /** Video width x height in pixels (already corrected for pixel aspect), or 0 to 0. */
    val videoSize: StateFlow<Pair<Int, Int>> = _videoSize.asStateFlow()
    val videoFormat: StateFlow<LightVideoFormat?> = _videoFormat.asStateFlow()
    val audioTracks: StateFlow<List<LightMediaTrack>> = _audioTracks.asStateFlow()
    val subtitleTracks: StateFlow<List<LightMediaTrack>> = _subtitleTracks.asStateFlow()
    /** The subtitle lines to show right now ("" when none); the tool draws them. */
    val subtitleText: StateFlow<String> = _subtitleText.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) startPositionUpdates() else stopPositionUpdates()
                updatePosition()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePosition()
                _ended.value = playbackState == Player.STATE_ENDED
            }

            override fun onPlayerErrorChanged(error: PlaybackException?) {
                _error.value = error?.let { LightVideoError(it.errorCode, it.errorCodeName) }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val width = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
                _videoSize.value = width to videoSize.height
            }

            override fun onTracksChanged(tracks: Tracks) {
                readTracks(tracks)
            }

            override fun onCues(cueGroup: CueGroup) {
                _subtitleText.value = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                _speed.value = playbackParameters.speed
            }
        })
    }

    /** Opens [file] (and any sidecar [subtitles]) at [startPositionMs]; call [play] to start. */
    fun setSource(file: File, startPositionMs: Long = 0L, subtitles: List<LightSubtitleFile> = emptyList()) {
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setSubtitleConfigurations(subtitles.map { it.toConfiguration() })
            .build()
        _ended.value = false
        _error.value = null
        player.setMediaItem(item, startPositionMs.coerceAtLeast(0L))
        player.prepare()
    }

    fun play() = player.play()

    fun pause() = player.pause()

    /** Seeks to [ms], clamped to the file. */
    fun seekTo(ms: Long) {
        val max = _durationMs.value.takeIf { it > 0L } ?: Long.MAX_VALUE
        player.seekTo(ms.coerceIn(0L, max))
        updatePosition()
    }

    /** Playback speed; pitch stays natural (ExoPlayer time-stretches the audio). */
    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed.coerceIn(MIN_SPEED, MAX_SPEED), 1f)
    }

    /** Selects one audio track by [LightMediaTrack.id]. */
    fun selectAudioTrack(id: String) = selectTrack(C.TRACK_TYPE_AUDIO, id)

    /** Selects one subtitle track, or turns subtitles off when [id] is null. */
    fun selectSubtitleTrack(id: String?) {
        if (id == null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            _subtitleText.value = ""
            return
        }
        selectTrack(C.TRACK_TYPE_TEXT, id)
    }

    /**
     * The video picture. [quarterTurns] turns it 90° per step (1 = landscape on a portrait
     * screen); the layout swaps width and height so the picture fills the turned space.
     * Keeps the screen on while shown. Scale/crop is up to the [modifier] the tool passes.
     */
    @Composable
    fun Surface(modifier: Modifier = Modifier, quarterTurns: Int = 0) {
        val turned = quarterTurns.mod(4) % 2 == 1
        AndroidView(
            modifier = modifier
                .layout { measurable, constraints ->
                    val inner = if (turned) {
                        constraints.copy(
                            minWidth = constraints.minHeight, maxWidth = constraints.maxHeight,
                            minHeight = constraints.minWidth, maxHeight = constraints.maxWidth,
                        )
                    } else {
                        constraints
                    }
                    val placeable = measurable.measure(inner)
                    val w = if (turned) placeable.height else placeable.width
                    val h = if (turned) placeable.width else placeable.height
                    layout(w, h) {
                        placeable.place((w - placeable.width) / 2, (h - placeable.height) / 2)
                    }
                }
                .graphicsLayer { rotationZ = 90f * quarterTurns.mod(4) },
            factory = { context ->
                TextureView(context).apply {
                    keepScreenOn = true
                    textureView = this
                    player.setVideoTextureView(this)
                }
            },
            onRelease = { view ->
                player.clearVideoTextureView(view)
                if (textureView === view) textureView = null
            },
        )
    }

    /** Stops playback and frees the decoder. Idempotent. */
    fun release() {
        if (released) return
        released = true
        stopPositionUpdates()
        textureView?.let { player.clearVideoTextureView(it) }
        textureView = null
        player.release()
        scope.cancel()
    }

    private fun selectTrack(trackType: Int, id: String) {
        val (groupIndex, trackIndex) = parseTrackId(id) ?: return
        val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return
        if (group.type != trackType) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun readTracks(tracks: Tracks) {
        val audio = mutableListOf<LightMediaTrack>()
        val text = mutableListOf<LightMediaTrack>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                val type = when (group.type) {
                    C.TRACK_TYPE_AUDIO -> LightTrackType.Audio
                    C.TRACK_TYPE_TEXT -> LightTrackType.Subtitle
                    else -> null
                }
                if (group.type == C.TRACK_TYPE_VIDEO && group.isTrackSelected(trackIndex)) {
                    _videoFormat.value = format.toLightVideoFormat()
                }
                type ?: continue
                val track = LightMediaTrack(
                    id = "$groupIndex:$trackIndex",
                    type = type,
                    label = format.label,
                    language = format.language,
                    codec = format.sampleMimeType ?: format.codecs,
                    isSelected = group.isTrackSelected(trackIndex),
                )
                if (type == LightTrackType.Audio) audio += track else text += track
            }
        }
        _audioTracks.value = audio
        _subtitleTracks.value = text
    }

    private fun startPositionUpdates() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch {
            while (isActive) {
                updatePosition()
                delay(POSITION_POLL_MS)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    private fun updatePosition() {
        if (released) return
        _positionMs.value = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration
        _durationMs.value = if (duration > 0L && duration != C.TIME_UNSET) duration else 0L
    }
}

/** "2:1" -> (2, 1); anything else -> null. */
internal fun parseTrackId(id: String): Pair<Int, Int>? {
    val parts = id.split(':')
    if (parts.size != 2) return null
    val group = parts[0].toIntOrNull() ?: return null
    val track = parts[1].toIntOrNull() ?: return null
    return group to track
}

private fun LightSubtitleFile.toConfiguration(): MediaItem.SubtitleConfiguration {
    val mime = when (file.extension.lowercase()) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }
    return MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
        .setMimeType(mime)
        .setLanguage(language)
        .setLabel(label)
        .build()
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun Format.toLightVideoFormat(): LightVideoFormat {
    fun Int.known() = takeIf { it != Format.NO_VALUE && it > 0 }
    return LightVideoFormat(
        width = width,
        height = height,
        codec = sampleMimeType,
        bitrate = averageBitrate.known() ?: peakBitrate.known() ?: bitrate.known(),
        frameRate = frameRate.takeIf { it > 0f },
    )
}

private const val POSITION_POLL_MS = 250L
private const val MIN_SPEED = 0.25f
private const val MAX_SPEED = 3.0f
