package com.thelightphone.reader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.reader.ReaderTheme
import kotlinx.coroutines.flow.first

/** Remembers the reading theme for the whole app, in the tool's DataStore (lightContext.dataStore). */
class ReaderThemePreference(private val dataStore: DataStore<Preferences>) {

    /** The saved theme, or Dark if nothing has been saved yet. */
    suspend fun load(): ReaderTheme =
        ReaderTheme.fromSavedName(dataStore.data.first()[KEY])

    suspend fun save(theme: ReaderTheme) {
        dataStore.edit { prefs -> prefs[KEY] = theme.name }
    }

    private companion object {
        val KEY = stringPreferencesKey("reader_theme")
    }
}
