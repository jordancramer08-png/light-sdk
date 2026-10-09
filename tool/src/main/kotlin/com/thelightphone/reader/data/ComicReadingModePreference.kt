package com.thelightphone.reader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.reader.comics.ComicReadingMode
import kotlinx.coroutines.flow.first

/**
 * Remembers whether comics are read a full page or a panel at a time, per series (the comic's
 * folder: key `comic_series:<folder>:mode`), in the tool's DataStore. A series with nothing
 * saved uses the mode used last anywhere (key `comic_reading_mode`), else Panels.
 */
class ComicReadingModePreference(private val dataStore: DataStore<Preferences>) {

    suspend fun load(folder: String): ComicReadingMode {
        val prefs = dataStore.data.first()
        return ComicReadingMode.fromSavedName(prefs[seriesKey(folder)] ?: prefs[KEY])
    }

    suspend fun save(folder: String, mode: ComicReadingMode) {
        dataStore.edit { prefs ->
            prefs[seriesKey(folder)] = mode.name
            prefs[KEY] = mode.name
        }
    }

    private fun seriesKey(folder: String) = stringPreferencesKey(ComicViewSettingsPreference.seriesKeyName(folder, "mode"))

    private companion object {
        val KEY = stringPreferencesKey("comic_reading_mode")
    }
}
