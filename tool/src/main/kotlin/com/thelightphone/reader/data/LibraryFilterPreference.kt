package com.thelightphone.reader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.reader.LibraryFilter
import kotlinx.coroutines.flow.first

/** Remembers which books the library shows (All, Want to Read, …), in the tool's DataStore. */
class LibraryFilterPreference(private val dataStore: DataStore<Preferences>) {

    /** The saved choice, or All if nothing has been saved yet. */
    suspend fun load(): LibraryFilter =
        LibraryFilter.fromSavedName(dataStore.data.first()[KEY])

    suspend fun save(filter: LibraryFilter) {
        dataStore.edit { prefs -> prefs[KEY] = filter.name }
    }

    private companion object {
        val KEY = stringPreferencesKey("library_filter")
    }
}
