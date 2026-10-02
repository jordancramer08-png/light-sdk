package com.thelightphone.listen.storage

import android.util.Log
import com.thelightphone.listen.music.ListSort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Listen's settings, saved in /sdcard/Listen/.state/settings.json so they survive an
 * uninstall and the PC script backs them up. Missing fields get these defaults and unknown
 * ones are ignored, so an older file (or one from a newer Listen) still loads.
 */
@Serializable
data class ListenSettings(
    val version: Int = 1,
    val songSort: ListSort = ListSort(),
    val albumSort: ListSort = ListSort(),
    val artistSort: ListSort = ListSort(),
    val bookSort: ListSort = ListSort(),
    /** Seconds an audiobook goes back when played again after a pause under 10 minutes. */
    val rewindShortSeconds: Int = 3,
    /** Seconds an audiobook goes back after a pause of 10 minutes or more. */
    val rewindLongSeconds: Int = 10,
    /** The color theme's name (see ListenTheme); anything unknown reads as Dark. */
    val theme: String = "DARK",
    /** Let the audio hardware decode (saves battery). Off until tested on the phone. */
    val audioOffload: Boolean = false,
    /** Podcast speed, one setting for every episode (1.0–2.0, pitch stays natural). */
    val podcastSpeed: Float = 1f,
    /** After an episode is played to the end: delete its download (false = keep it). */
    val deleteAfterFinishing: Boolean = false,
)

/** Reads and writes [ListenSettings] as JSON. A missing or broken file reads as the defaults. */
class SettingsFile(private val file: File) {

    fun load(): ListenSettings {
        val text = AtomicFile.readTextOrNull(file) ?: return ListenSettings()
        return try {
            json.decodeFromString(ListenSettings.serializer(), text)
        } catch (e: Exception) {
            ListenSettings()
        }
    }

    fun save(settings: ListenSettings) {
        AtomicFile.writeText(file, encode(settings))
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun encode(settings: ListenSettings): String = json.encodeToString(ListenSettings.serializer(), settings)
    }
}

/**
 * The app-wide settings. [load] reads settings.json once in the background (every screen
 * asks when it shows); [loaded] turns true after that, so lists can wait for the saved sort
 * instead of jumping. [change] updates at once and saves in the background.
 */
object Settings {
    private val _settings = MutableStateFlow(ListenSettings())
    val settings: StateFlow<ListenSettings> = _settings.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file by lazy { SettingsFile(File(ListenPaths.state, "settings.json")) }
    private var loadStarted = false

    fun load() {
        if (loadStarted) return
        loadStarted = true
        scope.launch {
            val saved = file.load()
            // A change made before the file was read wins over the saved value.
            if (!_loaded.value) _settings.value = saved
            _loaded.value = true
        }
    }

    fun change(update: (ListenSettings) -> ListenSettings) {
        _settings.update(update)
        _loaded.value = true
        scope.launch { write() }
    }

    @Synchronized
    private fun write() {
        try {
            file.save(_settings.value)
        } catch (e: Exception) {
            Log.w("Listen", "Couldn't save settings.json", e)
        }
    }
}
