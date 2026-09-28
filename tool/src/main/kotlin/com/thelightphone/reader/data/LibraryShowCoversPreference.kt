package com.thelightphone.reader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

/** Remembers whether the library shows book covers, in the tool's DataStore. */
class LibraryShowCoversPreference(private val dataStore: DataStore<Preferences>) {

    /** The saved choice, or On if nothing has been saved yet. */
    suspend fun load(): Boolean = dataStore.data.first()[KEY] ?: true

    suspend fun save(showCovers: Boolean) {
        dataStore.edit { prefs -> prefs[KEY] = showCovers }
    }

    private companion object {
        val KEY = booleanPreferencesKey("library_show_covers")
    }
}
