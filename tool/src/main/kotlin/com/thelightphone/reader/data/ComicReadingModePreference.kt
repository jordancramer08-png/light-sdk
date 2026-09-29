package com.thelightphone.reader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.reader.comics.ComicReadingMode
import kotlinx.coroutines.flow.first

/** Remembers whether comics are read a full page or a panel at a time, in the tool's DataStore. */
class ComicReadingModePreference(private val dataStore: DataStore<Preferences>) {

    /** The saved mode, or Panels if nothing (or something unknown) was saved. */
    suspend fun load(): ComicReadingMode = ComicReadingMode.fromSavedName(dataStore.data.first()[KEY])

    suspend fun save(mode: ComicReadingMode) {
        dataStore.edit { prefs -> prefs[KEY] = mode.name }
    }

    private companion object {
        val KEY = stringPreferencesKey("comic_reading_mode")
    }
}
