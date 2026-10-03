package com.thelightphone.listen.playback

import android.util.Log
import com.thelightphone.listen.books.Book
import com.thelightphone.listen.books.BookChapter
import com.thelightphone.listen.books.BookFile
import com.thelightphone.listen.books.BookLibrary
import com.thelightphone.listen.books.BookPosition
import com.thelightphone.listen.books.BookPositions
import com.thelightphone.listen.books.FINISHED_WITHIN_MS
import com.thelightphone.listen.books.bookPositionMs
import com.thelightphone.listen.books.chapterIndexAt
import com.thelightphone.listen.books.chaptersOf
import com.thelightphone.listen.books.isNearEnd
import com.thelightphone.listen.books.locate
import com.thelightphone.listen.books.rewindMs
import com.thelightphone.listen.music.MusicLibrary
import com.thelightphone.listen.music.Song
import com.thelightphone.listen.music.songFrom
import com.thelightphone.listen.podcasts.Podcasts
import com.thelightphone.listen.storage.ListenPaths
import com.thelightphone.listen.storage.Settings
import com.thelightphone.listen.storage.StorageAccess
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioErrorKind
import com.thelightphone.sdk.audio.LightAudioException
import com.thelightphone.sdk.audio.LightAudioFormat
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioPlayerAvailability
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.audio.LightMediaMetadata
import com.thelightphone.sdk.audio.LightRepeatMode
import com.thelightphone.sdk.audio.NO_MEDIA_ITEM
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** The loaded music queue, in its own (unshuffled) order. */
data class MusicQueue(val songs: List<Song>, val source: QueueSource)

/** A running sleep timer: pause at [endsAt] (wall clock), or at the end of the chapter. */
sealed interface SleepTimer {
    data class At(val endsAt: Long) : SleepTimer
    data object EndOfChapter : SleepTimer
}

/**
 * The one owner of Listen's player, for the whole app. The SDK allows one detached player
 * handle per process, and playback lives in the SDK's media service, so it keeps going with
 * the screen off, in other tools, and from the notification and Bluetooth buttons.
 *
 * Music and audiobooks share the one player and keep **separate** resume points: the music
 * spot in /sdcard/Listen/.state/music_state.json, each book's place in book_positions.json.
 * Starting a book saves the music spot first; [resumeMusic] puts it back (and saves the
 * book's place). music_state.json also says whether a book was loaded, so Listen reopens
 * whichever was playing last, **paused**.
 *
 * Every screen calls [attach] when it shows. The first time (or after the service stopped,
 * e.g. Listen was swiped away while paused) that opens a player and restores that spot.
 *
 * Saving: music every 5 seconds while playing, a book every 10 seconds, and both at once on
 * pause, skip, seek, chapter change, when Listen goes to the background and when the service
 * stops.
 *
 * Podcast episodes play through the book path as a one-file book (see [bookFor]); [episode]
 * says when the loaded book is really an episode. Then its place is saved in
 * podcast_episodes.json instead of book_positions.json, its speed is the one podcast speed,
 * and finishing it can delete its download (Settings).
 */
object PlaybackHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** One writer thread, so music_state.json writes land in the order they were asked for. */
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val store by lazy { MusicStateStore(File(ListenPaths.state, "music_state.json")) }

    private var audio: LightAudio? = null
    private var player: LightAudioPlayer? = null
    private var followJob: Job? = null
    private var restoreJob: Job? = null
    private var sleepJob: Job? = null
    /** Music attributes for songs, speech attributes for books (asked of the service on change). */
    private var usage = LightAudioUsage.Music

    private val _queue = MutableStateFlow<MusicQueue?>(null)
    private val _index = MutableStateFlow(NO_MEDIA_ITEM)
    private val _isPlaying = MutableStateFlow(false)
    private val _positionMs = MutableStateFlow(0L)
    private val _durationMs = MutableStateFlow(0L)
    private val _shuffle = MutableStateFlow(false)
    private val _repeat = MutableStateFlow(LightRepeatMode.Off)
    private val _message = MutableStateFlow<String?>(null)

    val queue: StateFlow<MusicQueue?> = _queue.asStateFlow()

    /** The audiobook loaded instead of music, or null. While a book is loaded the music queue is null. */
    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    /** The loaded book's chapters (empty while music is loaded). */
    private val _chapters = MutableStateFlow<List<BookChapter>>(emptyList())
    val chapters: StateFlow<List<BookChapter>> = _chapters.asStateFlow()

    /** The loaded book's speed (music always plays at 1.0). */
    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /** Set while the loaded book is a podcast episode (null for music and audiobooks). */
    private val _episode = MutableStateFlow<LoadedEpisode?>(null)
    val episode: StateFlow<LoadedEpisode?> = _episode.asStateFlow()

    /** An episode played to the end whose download is to be deleted once it stops (Settings). */
    private var deleteWhenDone: LoadedEpisode? = null

    private val _sleep = MutableStateFlow<SleepTimer?>(null)
    val sleep: StateFlow<SleepTimer?> = _sleep.asStateFlow()

    /** The music spot kept while a book plays (and the last one saved), or null. */
    private val _musicSpot = MutableStateFlow<MusicState?>(null)
    val musicSpot: StateFlow<MusicState?> = _musicSpot.asStateFlow()

    /** The index of the playing item: a song in the queue, or a file of the book. */
    val index: StateFlow<Int> = _index.asStateFlow()
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    /** Position in the current song or book file. */
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    /** The player's duration for the current song or book file, or 0 while it isn't known yet. */
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()
    val repeat: StateFlow<LightRepeatMode> = _repeat.asStateFlow()
    /** A short note for the user, such as "Can't play this file", or null. */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** The playing file's codec, bitrate and sample rate as the player read them, or null. */
    private val _format = MutableStateFlow<LightAudioFormat?>(null)
    val format: StateFlow<LightAudioFormat?> = _format.asStateFlow()

    /** The song at the current spot in the queue, or null when nothing is loaded. */
    val currentSong: StateFlow<Song?> = combine(_queue, _index) { queue, index ->
        queue?.songs?.getOrNull(index)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The index into [chapters] of the chapter playing, or -1 while no book is loaded. */
    private val _chapter = MutableStateFlow(-1)
    val chapter: StateFlow<Int> = _chapter.asStateFlow()

    /** Whether the user last asked to play (so an unplayable file is skipped and play continues). */
    private var wantsToPlay = false
    /** Unplayable files in a row; stops skipping once the whole queue has failed. */
    private var failuresInARow = 0
    private var lastSavedText: String? = null
    /** When the book was paused (wall clock), for the rewind when it plays again; null while playing. */
    private var pausedAt: Long? = null
    /** Whether the player was last asked to use audio offload. */
    private var offloadOn = false

    init {
        scope.launch {
            while (true) {
                delay(MUSIC_SAVE_EVERY_MS)
                if (_isPlaying.value && _book.value == null) saveMusic()
            }
        }
        scope.launch {
            while (true) {
                delay(BOOK_SAVE_EVERY_MS)
                if (_isPlaying.value && _book.value != null) saveBook()
            }
        }
        scope.launch {
            combine(_chapters, _index, _positionMs) { chapters, index, position ->
                if (chapters.isEmpty() || index < 0) -1
                else chapterIndexAt(chapters, BookPosition(fileIndex = index, positionMs = position))
            }.collect(::onChapter)
        }
        scope.launch {
            Settings.settings.map { it.audioOffload }.distinctUntilChanged().collect { applyAudioOffload() }
        }
    }

    /** Called by every screen when it shows: makes sure a player is open and the last spot restored. */
    fun attach(sealedActivity: SealedLightActivity) {
        audio = DefaultLightAudio(sealedActivity)
        ensurePlayer()
    }

    // ---- Music ----

    /**
     * Plays [songs] from [startIndex], replacing the queue. [shuffle] turns shuffle on or off
     * (album Play and Shuffle buttons); null keeps it as it is (tapping a song in a list).
     * With shuffle on, the tapped song plays first and every other song still plays once.
     */
    fun playSongs(songs: List<Song>, startIndex: Int, source: QueueSource, shuffle: Boolean? = null) {
        if (songs.isEmpty() || startIndex !in songs.indices) return
        restoreJob?.cancel()
        val player = ensurePlayer() ?: return
        leaveBook(player)
        _queue.value = MusicQueue(songs, source)
        _index.value = startIndex
        _positionMs.value = 0
        _durationMs.value = 0
        failuresInARow = 0
        wantsToPlay = true
        if (shuffle != null) {
            _shuffle.value = shuffle
            player.setShuffleEnabled(shuffle)
        }
        player.setMediaQueue(songs.map(::itemFor), startIndex)
        player.play()
        saveMusic()
    }

    /** Back from a book to the music spot saved when the book started, and plays it. */
    fun resumeMusic() {
        val saved = _musicSpot.value ?: return
        if (_book.value == null) return
        restoreJob?.cancel()
        val player = ensurePlayer() ?: return
        restoreJob = scope.launch {
            val state = withContext(Dispatchers.IO) {
                saved.restorable { File(ListenPaths.music, it).isFile }
            } ?: run {
                showMessage("That music isn't on the phone any more")
                return@launch
            }
            loadMusic(player, state, songsFor(state.paths), playNow = true)
            saveMusic()
        }
    }

    // ---- Podcasts ----

    /**
     * Plays a downloaded episode from where it was left (a little earlier after a pause, as
     * for books), or from the start when it was finished. The music spot or the book's place
     * is saved first. When it's already loaded, it just plays.
     */
    fun playEpisode(e: EpisodeToPlay) {
        if (_book.value?.id == episodeBookId(e.showId, e.episodeId)) {
            play()
            return
        }
        restoreJob?.cancel()
        val player = ensurePlayer() ?: return
        if (_book.value == null) saveMusic() else saveBook()
        val nearEnd = e.durationMs > 0 && e.durationMs - e.positionMs <= FINISHED_WITHIN_MS
        val start = if (e.played || nearEnd || e.positionMs <= 0) {
            0L
        } else {
            val back = rewindFor(System.currentTimeMillis() - (e.lastPlayedAt ?: 0))
            (e.positionMs - back).coerceAtLeast(0)
        }
        loadBook(player, bookFor(e), BookPosition(positionMs = start), podcastSpeed(), playNow = true, episode = e.loaded())
        saveBook()
    }

    private fun podcastSpeed(): Float = Settings.settings.value.podcastSpeed.coerceIn(PODCAST_SPEEDS.first(), PODCAST_SPEEDS.last())

    /** Deletes a finished episode's download once it has stopped at the end, or another item has taken its place. */
    private fun deleteFinishedDownload() {
        val done = deleteWhenDone ?: return
        val loaded = _episode.value
        val stillHere = loaded != null && loaded.isEpisode(done.showId, done.episodeId)
        val stoppedAtEnd = !_isPlaying.value && _durationMs.value > 0 && _durationMs.value - _positionMs.value <= ENDED_WITHIN_MS
        if (stillHere && !stoppedAtEnd) return
        deleteWhenDone = null
        Podcasts.removeDownload(done.showId, done.episodeId)
    }

    // ---- Audiobooks ----

    /**
     * Plays [book]: from [chapter] when given, else from its saved place (a little earlier,
     * see [rewindMs]), or from the start when it's new or finished. The music spot is saved
     * first and kept for [resumeMusic]. When the book is already loaded, it just plays (or
     * jumps to [chapter]).
     */
    fun playBook(book: Book, chapter: BookChapter? = null) {
        if (book.files.isEmpty()) return
        if (_book.value?.id == book.id) {
            chapter?.let(::seekToChapter)
            play()
            return
        }
        restoreJob?.cancel()
        val player = ensurePlayer() ?: return
        if (_book.value == null) saveMusic() else saveBook()

        val saved = BookPositions.positions.value[book.id]
        val speed = saved?.speed?.takeIf { it in SPEEDS } ?: 1f
        val start = when {
            chapter != null -> BookPosition(fileIndex = chapter.fileIndex, positionMs = chapter.startMs)
            saved == null -> BookPosition()
            isNearEnd(book, saved) -> BookPosition().also { startOverSaved(book, saved) }
            else -> {
                val back = rewindFor(System.currentTimeMillis() - saved.lastPlayedAt)
                clamp(book, saved).let { it.copy(positionMs = (it.positionMs - back).coerceAtLeast(0)) }
            }
        }
        loadBook(player, book, start, speed, playNow = true)
        saveBook()
    }

    /** Back to the start of the loaded [book]: not finished, speed kept. */
    fun startBookOver(book: Book) {
        val saved = BookPositions.positions.value[book.id] ?: BookPosition()
        startOverSaved(book, saved)
        if (_book.value?.id != book.id) return
        val player = ensurePlayer() ?: return
        pausedAt = null
        seekBookTo(player, BookPosition())
    }

    /** Back 15 s / forward 30 s through the whole book (across files). */
    fun skipBook(deltaMs: Long) {
        val book = _book.value ?: return
        val player = ensurePlayer() ?: return
        val here = BookPosition(fileIndex = _index.value.coerceAtLeast(0), positionMs = _positionMs.value)
        val target = if (book.files.all { it.durationMs > 0 }) {
            locate(book, bookPositionMs(book, here) + deltaMs)
        } else {
            // Lengths not known yet: stay inside this file.
            val end = _durationMs.value.takeIf { it > 0 } ?: Long.MAX_VALUE
            here.copy(positionMs = (here.positionMs + deltaMs).coerceIn(0, end))
        }
        seekBookTo(player, target)
    }

    /** The start of this chapter, or of the previous one when already near the start. */
    fun previousChapter() {
        val chapters = _chapters.value
        val i = _chapter.value
        val current = chapters.getOrNull(i) ?: return
        val intoChapter = _positionMs.value - current.startMs
        val target = if (intoChapter > RESTART_THRESHOLD_MS || i == 0) current else chapters[i - 1]
        seekToChapter(target)
    }

    fun nextChapter() {
        val next = _chapters.value.getOrNull(_chapter.value + 1) ?: return
        seekToChapter(next)
    }

    fun seekToChapter(chapter: BookChapter) {
        val player = ensurePlayer() ?: return
        seekBookTo(player, BookPosition(fileIndex = chapter.fileIndex, positionMs = chapter.startMs))
    }

    /** Pitch-corrected speed for the loaded book, remembered in its place. */
    fun setSpeed(speed: Float) {
        if (_book.value == null) return
        val player = ensurePlayer() ?: return
        _speed.value = speed
        player.speed = speed
        // An episode's speed is the one podcast speed, not remembered per episode.
        if (_episode.value != null) Settings.change { it.copy(podcastSpeed = speed) }
        applyAudioOffload()
        saveBook()
    }

    /** Pauses after [minutes], or at the end of the chapter when null. */
    fun startSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) {
            _sleep.value = SleepTimer.EndOfChapter
            return
        }
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _sleep.value = SleepTimer.At(endsAt)
        sleepJob = scope.launch {
            delay(endsAt - System.currentTimeMillis())
            _sleep.value = null
            pause()
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        _sleep.value = null
    }

    // ---- Both ----

    fun togglePlayPause() {
        if (_isPlaying.value) pause() else play()
    }

    fun play() {
        if (_queue.value == null && _book.value == null) return
        val player = ensurePlayer() ?: return
        val book = _book.value
        val episode = _episode.value
        if (book != null && episode != null) {
            if (!episode.streaming && !episode.audio.isFile) {
                showMessage("This episode's download was removed")
                return
            }
            // Played to the end: starts again from the beginning.
            if (isAtBookEnd(book, currentBookPosition(saved = null))) {
                pausedAt = null
                seekBookTo(player, BookPosition())
            } else {
                rewindAfterPause(player)
            }
        } else if (book != null) {
            // Played to the end: starts again from the beginning.
            if (isAtBookEnd(book, currentBookPosition(saved = null))) {
                startBookOver(book)
            } else {
                rewindAfterPause(player)
            }
        }
        wantsToPlay = true
        failuresInARow = 0
        // After an error or the end of the queue, the service prepares or rewinds first.
        player.play()
    }

    fun pause() {
        wantsToPlay = false
        player?.pause()
    }

    fun next() {
        val player = ensurePlayer() ?: return
        player.skipToNext()
        saveSoon()
    }

    /** Back to the start of the song, or to the previous song when already near the start. */
    fun previous() {
        val player = ensurePlayer() ?: return
        if (_positionMs.value > RESTART_THRESHOLD_MS) {
            player.seekTo(0)
        } else {
            player.skipToPrevious()
        }
        saveSoon()
    }

    /** Seeks in the current song or book file. */
    fun seekTo(ms: Long) {
        val player = ensurePlayer() ?: return
        if (_book.value != null) {
            seekBookTo(player, BookPosition(fileIndex = _index.value.coerceAtLeast(0), positionMs = ms))
            return
        }
        _positionMs.value = ms
        player.seekTo(ms)
        saveSoon()
    }

    fun toggleShuffle() {
        if (_book.value != null) return
        val player = ensurePlayer() ?: return
        val on = !_shuffle.value
        _shuffle.value = on
        player.setShuffleEnabled(on)
        saveSoon()
    }

    /** Off → All → One → Off. */
    fun cycleRepeat() {
        if (_book.value != null) return
        val player = ensurePlayer() ?: return
        val mode = when (_repeat.value) {
            LightRepeatMode.Off -> LightRepeatMode.All
            LightRepeatMode.All -> LightRepeatMode.One
            LightRepeatMode.One -> LightRepeatMode.Off
        }
        _repeat.value = mode
        player.setRepeatMode(mode)
        saveSoon()
    }

    /** Saves the music spot or the book's place now (Listen is going to the background). */
    fun saveNow() {
        saveCurrent()
    }

    // ---- Loading ----

    private fun loadMusic(player: LightAudioPlayer, state: MusicState, songs: List<Song>, playNow: Boolean) {
        leaveBook(player)
        val repeat = repeatFrom(state.repeat)
        _queue.value = MusicQueue(songs, state.source)
        _index.value = state.index
        _positionMs.value = state.positionMs
        _durationMs.value = 0
        _shuffle.value = state.shuffle
        _repeat.value = repeat
        failuresInARow = 0
        wantsToPlay = playNow
        _musicSpot.value = state.copy(book = null)
        player.setShuffleEnabled(state.shuffle)
        player.setRepeatMode(repeat)
        player.setMediaQueue(songs.map(::itemFor), state.index, state.positionMs)
        if (playNow) player.play()
    }

    /** Before music loads: saves the book's place and puts music's settings back on the player. */
    private fun leaveBook(player: LightAudioPlayer) {
        if (_book.value != null) saveBook()
        cancelSleepTimer()
        _book.value = null
        _episode.value = null
        deleteFinishedDownload()
        _chapters.value = emptyList()
        pausedAt = null
        setUsage(player, LightAudioUsage.Music)
        player.speed = 1f
        applyAudioOffload()
        player.setShuffleEnabled(_shuffle.value)
        player.setRepeatMode(_repeat.value)
    }

    private fun loadBook(
        player: LightAudioPlayer,
        book: Book,
        start: BookPosition,
        speed: Float,
        playNow: Boolean,
        episode: LoadedEpisode? = null,
    ) {
        if (_book.value?.id != book.id) cancelSleepTimer()
        _queue.value = null
        _book.value = book
        _episode.value = episode
        lastEpisodeSaveMs = -1
        deleteFinishedDownload()
        _chapters.value = chaptersOf(book)
        _index.value = start.fileIndex
        _positionMs.value = start.positionMs
        _durationMs.value = 0
        _speed.value = speed
        failuresInARow = 0
        wantsToPlay = playNow
        pausedAt = null
        setUsage(player, LightAudioUsage.Speech)
        player.setShuffleEnabled(false)
        player.setRepeatMode(LightRepeatMode.Off)
        player.speed = speed
        applyAudioOffload()
        val stream = episode?.streamUrl
        val items = if (stream != null) listOf(streamItem(book, stream)) else book.files.map { itemFor(book, it) }
        player.setMediaQueue(items, start.fileIndex, start.positionMs)
        markBookLoaded(book.id)
        if (playNow) player.play()
    }

    private fun setUsage(player: LightAudioPlayer, wanted: LightAudioUsage) {
        if (usage == wanted) return
        usage = wanted
        player.setUsage(wanted)
    }

    /** A user seek in the book: moves the player, and saves the new place. */
    private fun seekBookTo(player: LightAudioPlayer, target: BookPosition) {
        val book = _book.value ?: return
        val clamped = clamp(book, target)
        // A chosen spot plays as it is, without the rewind.
        pausedAt = null
        _index.value = clamped.fileIndex
        _positionMs.value = clamped.positionMs
        // A seek moves the end-of-chapter timer on to the chapter it lands in.
        sleepChapter = chapterIndexAt(_chapters.value, clamped)
        player.seekTo(clamped.fileIndex, clamped.positionMs)
        saveSoon()
    }

    /** [position] made to fit [book]: a file that exists and a place inside it. */
    private fun clamp(book: Book, position: BookPosition): BookPosition {
        val index = position.fileIndex.coerceIn(0, book.files.lastIndex)
        val length = book.files[index].durationMs
        val ms = position.positionMs.coerceAtLeast(0).let { if (length > 0) it.coerceAtMost(length) else it }
        return position.copy(fileIndex = index, positionMs = ms)
    }

    private fun startOverSaved(book: Book, saved: BookPosition) {
        BookPositions.record(
            book.id,
            saved.copy(fileIndex = 0, positionMs = 0, finished = false, lastPlayedAt = System.currentTimeMillis()),
        )
    }

    // ---- Rewind and sleep ----

    private fun rewindFor(pausedForMs: Long): Long {
        val settings = Settings.settings.value
        return rewindMs(pausedForMs, settings.rewindShortSeconds, settings.rewindLongSeconds)
    }

    /** Before a paused book plays again: goes back a little, more after a long pause. */
    private fun rewindAfterPause(player: LightAudioPlayer) {
        val at = pausedAt ?: return
        pausedAt = null
        val back = rewindFor(System.currentTimeMillis() - at)
        if (back <= 0) return
        val target = BookPosition(fileIndex = _index.value.coerceAtLeast(0), positionMs = (_positionMs.value - back).coerceAtLeast(0))
        _positionMs.value = target.positionMs
        player.seekTo(target.fileIndex, target.positionMs)
    }

    /** The chapter an end-of-chapter timer waits to finish. */
    private var sleepChapter = -1

    private fun onChapter(index: Int) {
        val previous = _chapter.value
        _chapter.value = index
        if (index < 0 || previous < 0 || index == previous) {
            sleepChapter = index
            return
        }
        // Played on into another chapter.
        if (_sleep.value == SleepTimer.EndOfChapter && index != sleepChapter && _isPlaying.value) {
            _sleep.value = null
            pause()
            _chapters.value.getOrNull(index)?.let { seekToChapter(it) }
        }
        sleepChapter = index
        saveSoon()
    }

    // ---- Audio offload ----

    /**
     * Audio offload (Settings, off by default) hands decoding to the phone's audio hardware,
     * which saves battery. It's only used at 1.0× speed: other speeds need the CPU.
     */
    private fun applyAudioOffload() {
        val player = player?.takeIf { it.availability.value != LightAudioPlayerAvailability.Released } ?: return
        val speed = if (_book.value != null) _speed.value else 1f
        val wanted = Settings.settings.value.audioOffload && speed == 1f
        offloadOn = wanted
        player.setAudioOffload(wanted)
    }

    /**
     * Playback went wrong while offload was on: switch the setting off and try the same file
     * again, since offload is the likelier cause than the file.
     */
    private fun turnOffloadOffAfterError(player: LightAudioPlayer) {
        Settings.change { it.copy(audioOffload = false) }
        offloadOn = false
        player.setAudioOffload(false)
        showMessage("Audio offload switched off")
        player.prepare()
        if (wantsToPlay) player.play()
    }

    // ---- Player lifecycle ----

    /** The open player, opening a new one if there is none or the old one was released. */
    private fun ensurePlayer(): LightAudioPlayer? {
        player?.takeIf { it.availability.value != LightAudioPlayerAvailability.Released }?.let { return it }
        if (!StorageAccess.hasAllFilesAccess()) return null
        val factory = audio ?: return null
        val opened = try {
            factory.newPlayer(usage, LightAudioPlayback.Detached)
        } catch (e: LightAudioException) {
            Log.w("Listen", "Couldn't open the player", e)
            return null
        }
        player = opened
        offloadOn = false
        applyAudioOffload()
        follow(opened)
        restoreJob = scope.launch { restoreInto(opened) }
        return opened
    }

    /**
     * After connecting: if the service is still playing our queue (Listen was reopened while
     * it played), keep it. If the service is new and empty, load the saved book or music
     * spot, paused.
     */
    private suspend fun restoreInto(player: LightAudioPlayer) {
        if (!player.awaitReady()) return
        val loadedBook = _book.value
        if (player.currentMediaItemIndex.value != NO_MEDIA_ITEM && (_queue.value != null || loadedBook != null)) return

        // The service restarted under a loaded book: load its saved place again.
        if (loadedBook != null) {
            restoreBook(player, loadedBook.id)
            return
        }
        val saved = _musicSpot.value ?: withContext(Dispatchers.IO) { store.load() } ?: return
        _musicSpot.value = saved
        if (saved.book != null && restoreBook(player, saved.book)) return

        val state = withContext(Dispatchers.IO) {
            saved.restorable { File(ListenPaths.music, it).isFile }
        } ?: return
        loadMusic(player, state, songsFor(state.paths), playNow = false)
    }

    /** Loads book [id] paused at its saved place; false when it isn't on the phone. */
    private suspend fun restoreBook(player: LightAudioPlayer, id: String): Boolean {
        if (id.startsWith(EPISODE_ID_PREFIX)) return restoreEpisode(player, id)
        withTimeoutOrNull(LIBRARY_WAIT_MS) { BookLibrary.state.first { it.loaded } }
        withTimeoutOrNull(LIBRARY_WAIT_MS) { BookPositions.loaded.first { it } }
        val book = BookLibrary.state.value.book(id)?.takeIf { it.files.isNotEmpty() } ?: return false
        val saved = BookPositions.positions.value[id] ?: BookPosition()
        loadBook(player, book, clamp(book, saved), saved.speed.takeIf { it in SPEEDS } ?: 1f, playNow = false)
        // The pause started when it was last played, so the rewind fits how long ago that was.
        pausedAt = saved.lastPlayedAt.takeIf { it > 0 }
        return true
    }

    /** Loads episode [id] ("podcast:showId/episodeId") paused at its saved place; false when its download is gone. */
    private suspend fun restoreEpisode(player: LightAudioPlayer, id: String): Boolean {
        val parts = id.removePrefix(EPISODE_ID_PREFIX).split('/', limit = 2)
        if (parts.size != 2) return false
        val e = Podcasts.episodeToPlay(parts[0], parts[1]) ?: return false
        val start = if (e.played) 0L else e.positionMs
        loadBook(player, bookFor(e), BookPosition(positionMs = start), podcastSpeed(), playNow = false, episode = e.loaded())
        pausedAt = e.lastPlayedAt
        return true
    }

    /** Library songs for [paths], waiting briefly for the library; a file-name fallback otherwise. */
    private suspend fun songsFor(paths: List<String>): List<Song> {
        withTimeoutOrNull(LIBRARY_WAIT_MS) { MusicLibrary.state.first { it.loaded } }
        val known = MusicLibrary.state.value.songs.associateBy { it.path }
        return paths.map { path -> known[path] ?: songFrom(path, 0, 0, tags = null) }
    }

    /** Mirrors [player]'s state into the hub's flows, for as long as it's the open player. */
    private fun follow(player: LightAudioPlayer) {
        followJob?.cancel()
        followJob = scope.launch {
            val hasQueue = { player.currentMediaItemIndex.value != NO_MEDIA_ITEM }
            launch {
                player.isPlaying.collect { playing ->
                    _isPlaying.value = playing
                    if (playing) {
                        wantsToPlay = true
                        failuresInARow = 0
                        _message.value = null
                        // Played again from the notification or a headset: rewind here instead.
                        if (_book.value != null) rewindAfterPause(player)
                    } else {
                        if (_book.value != null && pausedAt == null) pausedAt = System.currentTimeMillis()
                        saveSoon()
                        scope.launch {
                            delay(SETTLE_MS * 2)
                            deleteFinishedDownload()
                        }
                    }
                }
            }
            launch {
                player.currentMediaItemIndex.collect { index ->
                    if (index == NO_MEDIA_ITEM) return@collect
                    if (index != _index.value) {
                        _index.value = index
                        saveSoon()
                    }
                }
            }
            launch { player.positionMs.collect { if (hasQueue()) _positionMs.value = it } }
            launch { player.durationMs.collect { if (hasQueue()) _durationMs.value = it } }
            // While a book plays, these keep music's shuffle and repeat for when music comes back.
            launch { player.shuffleEnabled.collect { if (hasQueue() && _book.value == null) _shuffle.value = it } }
            launch { player.repeatMode.collect { if (hasQueue() && _book.value == null) _repeat.value = it } }
            launch { player.error.collect { error -> if (error != null) onError(player, error) } }
            launch { player.currentFormat.collect { _format.value = it } }
            launch {
                // The service stopped (e.g. swiped away while paused). Save, and keep showing
                // what was loaded, paused; the next attach opens a fresh player and restores it.
                player.availability.first { it == LightAudioPlayerAvailability.Released }
                saveCurrent()
                _isPlaying.value = false
                if (this@PlaybackHub.player === player) usage = LightAudioUsage.Music
            }
        }
    }

    /** "Can't play this file", then on to the next song or file (never a crash, never a loop). */
    private fun onError(player: LightAudioPlayer, error: LightAudioError) {
        Log.w("Listen", "Playback error ${error.diagnostic} at item ${error.itemIndex}")
        if (offloadOn) {
            turnOffloadOffAfterError(player)
            return
        }
        // A stream that lost its connection (no signal, or Wi-Fi to mobile data took too long):
        // stay on it, keep the place, and say so; Play carries on from there.
        if (_episode.value?.streaming == true && error.kind == LightAudioErrorKind.Source) {
            wantsToPlay = false
            saveSoon()
            // Stays until playing again (the isPlaying follower clears it), so it isn't missed.
            _message.value = if (Podcasts.isOnline()) STREAM_LOST else STREAM_OFFLINE
            return
        }
        showMessage(CANT_PLAY)
        val isBook = _book.value != null
        val size = _queue.value?.songs?.size ?: _book.value?.files?.size ?: return
        failuresInARow++
        val atEnd = _index.value >= size - 1 && (isBook || (_repeat.value == LightRepeatMode.Off && !_shuffle.value))
        if (failuresInARow >= size || atEnd) return
        player.skipToNext()
        if (wantsToPlay) player.play() else player.prepare()
    }

    private fun showMessage(text: String) {
        _message.value = text
        scope.launch {
            delay(MESSAGE_MS)
            if (_message.value == text) _message.value = null
        }
    }

    // ---- Saving ----

    /** Saves once the player has reported its settled position (after a pause or skip). */
    private fun saveSoon() {
        scope.launch {
            delay(SETTLE_MS)
            saveCurrent()
        }
    }

    private fun saveCurrent() {
        if (_book.value != null) saveBook() else saveMusic()
    }

    /** The loaded book's place now; [saved] supplies what the player doesn't know (finished). */
    private fun currentBookPosition(saved: BookPosition?) = BookPosition(
        fileIndex = _index.value,
        positionMs = _positionMs.value,
        speed = _speed.value,
        lastPlayedAt = saved?.lastPlayedAt ?: 0,
        finished = saved?.finished ?: false,
    )

    /**
     * Saves the loaded book's place in book_positions.json. It counts as finished once the
     * last 30 seconds are reached. "Last played" moves on only while it plays or the place
     * changed, so the rewind knows how long a pause really was.
     */
    private fun saveBook() {
        val book = _book.value ?: return
        if (_index.value !in book.files.indices) return
        _episode.value?.let {
            saveEpisode(it, book)
            return
        }
        val saved = BookPositions.positions.value[book.id]
        val here = currentBookPosition(saved)
        val moved = saved == null || saved.fileIndex != here.fileIndex || saved.positionMs != here.positionMs
        BookPositions.record(
            book.id,
            here.copy(
                lastPlayedAt = if (_isPlaying.value || moved) System.currentTimeMillis() else here.lastPlayedAt,
                // "Mark finished" holds until the place moves on.
                finished = isAtBookEnd(book, here) || (here.finished && !moved),
            ),
        )
    }

    /** The last position saved for the loaded episode, so a paused one doesn't count as "played just now". */
    private var lastEpisodeSaveMs = -1L

    /**
     * Saves the loaded episode's place in podcast_episodes.json. Reaching its last 30 seconds
     * (or 95%) marks it played; with "After finishing: Delete download", the download goes
     * once it stops at the end or something else is played.
     */
    private fun saveEpisode(e: LoadedEpisode, book: Book) {
        val position = _positionMs.value
        val duration = _durationMs.value.takeIf { it > 0 } ?: book.files.first().durationMs
        val touch = _isPlaying.value || position != lastEpisodeSaveMs
        lastEpisodeSaveMs = position
        Podcasts.savePlayback(e.showId, e.episodeId, position, duration, touch) {
            scope.launch {
                if (Settings.settings.value.deleteAfterFinishing) deleteWhenDone = e
            }
        }
    }

    /** In the book's last 30 seconds, by the index's lengths or the player's for the last file. */
    private fun isAtBookEnd(book: Book, here: BookPosition): Boolean {
        val lastFile = here.fileIndex == book.files.lastIndex
        val playerNearEnd = lastFile && _durationMs.value > 0 && _durationMs.value - here.positionMs <= FINISHED_WITHIN_MS
        return playerNearEnd || isNearEnd(book, here)
    }

    private fun saveMusic() {
        val queue = _queue.value ?: return
        val index = _index.value
        if (index !in queue.songs.indices) return
        val state = MusicState(
            source = queue.source,
            paths = queue.songs.map { it.path },
            index = index,
            positionMs = _positionMs.value,
            shuffle = _shuffle.value,
            repeat = repeatName(_repeat.value),
        )
        _musicSpot.value = state
        scope.launch(io) { write(state) }
    }

    /** Notes in music_state.json that book [id] is loaded, keeping the music spot as it was. */
    private fun markBookLoaded(id: String) {
        val state = (_musicSpot.value ?: MusicState()).copy(book = id)
        scope.launch(io) { write(state) }
    }

    @Synchronized
    private fun write(state: MusicState) {
        val text = MusicStateStore.encode(state)
        if (text == lastSavedText) return
        try {
            store.save(state)
            lastSavedText = text
        } catch (e: Exception) {
            Log.w("Listen", "Couldn't save music_state.json", e)
        }
    }

    private fun itemFor(song: Song) = LightAudioItem(
        source = LightAudioSource.FileSource(File(ListenPaths.music, song.path)),
        metadata = LightMediaMetadata(
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.durationMs.takeIf { it > 0 },
        ),
    )

    private fun itemFor(book: Book, file: BookFile) = LightAudioItem(
        source = LightAudioSource.FileSource(File(File(ListenPaths.audiobooks, book.folder), file.path)),
        metadata = LightMediaMetadata(
            title = file.label.ifBlank { book.title },
            artist = book.author,
            album = book.title,
            durationMs = file.durationMs.takeIf { it > 0 },
        ),
    )

    /** A streamed episode: its one "file" is a web address. */
    private fun streamItem(book: Book, url: String) = LightAudioItem(
        source = LightAudioSource.UrlSource(url),
        metadata = LightMediaMetadata(
            title = book.title,
            artist = book.author,
            album = book.author,
            durationMs = book.files.firstOrNull()?.durationMs?.takeIf { it > 0 },
        ),
    )

    private fun repeatName(mode: LightRepeatMode) = when (mode) {
        LightRepeatMode.Off -> MusicState.REPEAT_OFF
        LightRepeatMode.All -> MusicState.REPEAT_ALL
        LightRepeatMode.One -> MusicState.REPEAT_ONE
    }

    private fun repeatFrom(name: String) = when (name) {
        MusicState.REPEAT_ALL -> LightRepeatMode.All
        MusicState.REPEAT_ONE -> LightRepeatMode.One
        else -> LightRepeatMode.Off
    }

    /** The audiobook speeds on offer (pitch stays natural at every one). */
    val SPEEDS = listOf(0.75f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f)

    /** The sleep timer choices in minutes (plus "end of chapter"). */
    val SLEEP_MINUTES = listOf(15, 30, 45, 60)

    private const val MUSIC_SAVE_EVERY_MS = 5_000L
    private const val BOOK_SAVE_EVERY_MS = 10_000L
    private const val SETTLE_MS = 300L
    private const val RESTART_THRESHOLD_MS = 3_000L
    private const val LIBRARY_WAIT_MS = 5_000L
    private const val MESSAGE_MS = 4_000L
    private const val CANT_PLAY = "Can't play this file"
    private const val STREAM_LOST = "Lost the stream. Tap play to carry on"
    private const val STREAM_OFFLINE = "No connection. Tap play when you're back online"

    /** An episode stopped this close to its end has finished playing. */
    private const val ENDED_WITHIN_MS = 3_000L
}
