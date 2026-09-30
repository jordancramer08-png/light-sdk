package com.thelightphone.listen.playback

import android.util.Log
import com.thelightphone.listen.books.Book
import com.thelightphone.listen.books.BookFile
import com.thelightphone.listen.music.MusicLibrary
import com.thelightphone.listen.music.Song
import com.thelightphone.listen.music.songFrom
import com.thelightphone.listen.storage.ListenPaths
import com.thelightphone.listen.storage.StorageAccess
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioException
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** The loaded music queue, in its own (unshuffled) order. */
data class MusicQueue(val songs: List<Song>, val source: QueueSource)

/**
 * The one owner of Listen's player, for the whole app. The SDK allows one detached player
 * handle per process, and playback lives in the SDK's media service, so it keeps going with
 * the screen off, in other tools, and from the notification and Bluetooth buttons.
 *
 * Every screen calls [attach] when it shows. The first time (or after the service stopped,
 * e.g. Listen was swiped away while paused) that opens a player and puts the saved music
 * queue back, **paused** at the saved spot, from /sdcard/Listen/.state/music_state.json.
 *
 * The music spot is saved every 5 seconds while playing, and at once on pause, skip, seek,
 * shuffle/repeat changes and when Listen goes to the background.
 */
object PlaybackHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store by lazy { MusicStateStore(File(ListenPaths.state, "music_state.json")) }

    private var audio: LightAudio? = null
    private var player: LightAudioPlayer? = null
    private var followJob: Job? = null
    private var restoreJob: Job? = null

    private val _queue = MutableStateFlow<MusicQueue?>(null)
    private val _index = MutableStateFlow(NO_MEDIA_ITEM)
    private val _isPlaying = MutableStateFlow(false)
    private val _positionMs = MutableStateFlow(0L)
    private val _durationMs = MutableStateFlow(0L)
    private val _shuffle = MutableStateFlow(false)
    private val _repeat = MutableStateFlow(LightRepeatMode.Off)
    private val _message = MutableStateFlow<String?>(null)

    val queue: StateFlow<MusicQueue?> = _queue.asStateFlow()

    /**
     * The audiobook loaded instead of music, or null. While a book is loaded the music queue
     * is null, and music_state.json is left alone (it keeps the music spot saved just before).
     * Book positions are saved from Session 6.
     */
    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    /** The index of the playing item: a song in the queue, or a file of the book. */
    val index: StateFlow<Int> = _index.asStateFlow()
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    /** The player's duration for the current song, or 0 while it isn't known yet. */
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()
    val repeat: StateFlow<LightRepeatMode> = _repeat.asStateFlow()
    /** A short note for the user, such as "Can't play this file", or null. */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** The song at the current spot in the queue, or null when nothing is loaded. */
    val currentSong: StateFlow<Song?> = combine(_queue, _index) { queue, index ->
        queue?.songs?.getOrNull(index)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Whether the user last asked to play (so an unplayable file is skipped and play continues). */
    private var wantsToPlay = false
    /** Unplayable files in a row; stops skipping once the whole queue has failed. */
    private var failuresInARow = 0
    /** The last spot written to music_state.json (also used to restore without re-reading it). */
    private var lastSaved: MusicState? = null
    private var lastSavedText: String? = null

    init {
        scope.launch {
            while (true) {
                delay(SAVE_EVERY_MS)
                if (_isPlaying.value) save()
            }
        }
    }

    /** Called by every screen when it shows: makes sure a player is open and the music spot restored. */
    fun attach(sealedActivity: SealedLightActivity) {
        audio = DefaultLightAudio(sealedActivity)
        ensurePlayer()
    }

    /**
     * Plays [songs] from [startIndex], replacing the queue. [shuffle] turns shuffle on or off
     * (album Play and Shuffle buttons); null keeps it as it is (tapping a song in a list).
     * With shuffle on, the tapped song plays first and every other song still plays once.
     */
    fun playSongs(songs: List<Song>, startIndex: Int, source: QueueSource, shuffle: Boolean? = null) {
        if (songs.isEmpty() || startIndex !in songs.indices) return
        restoreJob?.cancel()
        val player = ensurePlayer() ?: return
        if (_book.value != null) {
            _book.value = null
            player.setShuffleEnabled(_shuffle.value)
            player.setRepeatMode(_repeat.value)
        }
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
        save()
    }

    /**
     * Plays [book] from the start of its first file. The music spot is saved first and then
     * kept as it is; music's shuffle and repeat are turned off on the player (and put back
     * when music plays again).
     */
    fun playBook(book: Book) {
        if (book.files.isEmpty()) return
        restoreJob?.cancel()
        val player = ensurePlayer() ?: return
        save()
        _queue.value = null
        _book.value = book
        _index.value = 0
        _positionMs.value = 0
        _durationMs.value = 0
        failuresInARow = 0
        wantsToPlay = true
        player.setShuffleEnabled(false)
        player.setRepeatMode(LightRepeatMode.Off)
        player.setMediaQueue(book.files.map { itemFor(book, it) }, 0)
        player.play()
    }

    fun togglePlayPause() {
        if (_isPlaying.value) pause() else play()
    }

    fun play() {
        if (_queue.value == null && _book.value == null) return
        val player = ensurePlayer() ?: return
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

    fun seekTo(ms: Long) {
        val player = ensurePlayer() ?: return
        _positionMs.value = ms
        player.seekTo(ms)
        saveSoon()
    }

    fun toggleShuffle() {
        val player = ensurePlayer() ?: return
        val on = !_shuffle.value
        _shuffle.value = on
        player.setShuffleEnabled(on)
        saveSoon()
    }

    /** Off → All → One → Off. */
    fun cycleRepeat() {
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

    /** Saves the music spot now (Listen is going to the background). */
    fun saveNow() {
        save()
    }

    // ---- Player lifecycle ----

    /** The open player, opening a new one if there is none or the old one was released. */
    private fun ensurePlayer(): LightAudioPlayer? {
        player?.takeIf { it.availability.value != LightAudioPlayerAvailability.Released }?.let { return it }
        if (!StorageAccess.hasAllFilesAccess()) return null
        val factory = audio ?: return null
        val opened = try {
            factory.newPlayer(LightAudioUsage.Music, LightAudioPlayback.Detached)
        } catch (e: LightAudioException) {
            Log.w("Listen", "Couldn't open the player", e)
            return null
        }
        player = opened
        follow(opened)
        restoreJob = scope.launch { restoreInto(opened) }
        return opened
    }

    /**
     * After connecting: if the service is still playing our queue (Listen was reopened while
     * music played), keep it. If the service is new and empty, load the saved spot, paused.
     */
    private suspend fun restoreInto(player: LightAudioPlayer) {
        if (!player.awaitReady()) return
        if (player.currentMediaItemIndex.value != NO_MEDIA_ITEM && (_queue.value != null || _book.value != null)) return

        val saved = lastSaved ?: withContext(Dispatchers.IO) { store.load() } ?: return
        val state = withContext(Dispatchers.IO) {
            saved.restorable { File(ListenPaths.music, it).isFile }
        } ?: return
        val songs = songsFor(state.paths)

        val shuffle = state.shuffle
        val repeat = repeatFrom(state.repeat)
        _queue.value = MusicQueue(songs, state.source)
        _index.value = state.index
        _positionMs.value = state.positionMs
        _durationMs.value = 0
        _shuffle.value = shuffle
        _repeat.value = repeat
        wantsToPlay = false
        lastSaved = state
        _book.value = null
        player.setShuffleEnabled(shuffle)
        player.setRepeatMode(repeat)
        player.setMediaQueue(songs.map(::itemFor), state.index, state.positionMs)
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
                    } else {
                        saveSoon()
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
            launch {
                // The service stopped (e.g. swiped away while paused). Keep showing the
                // song, paused; the next attach opens a fresh player and restores it.
                player.availability.first { it == LightAudioPlayerAvailability.Released }
                _isPlaying.value = false
            }
        }
    }

    /** "Can't play this file", then on to the next song (never a crash, never a loop). */
    private fun onError(player: LightAudioPlayer, error: LightAudioError) {
        Log.w("Listen", "Playback error ${error.diagnostic} at item ${error.itemIndex}")
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
            save()
        }
    }

    private fun save() {
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
        lastSaved = state
        scope.launch(Dispatchers.IO) { write(state) }
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

    private const val SAVE_EVERY_MS = 5_000L
    private const val SETTLE_MS = 300L
    private const val RESTART_THRESHOLD_MS = 3_000L
    private const val LIBRARY_WAIT_MS = 5_000L
    private const val MESSAGE_MS = 4_000L
    private const val CANT_PLAY = "Can't play this file"
}
