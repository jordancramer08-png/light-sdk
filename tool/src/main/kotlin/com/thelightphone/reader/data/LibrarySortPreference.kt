package com.thelightphone.reader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.reader.LibrarySort
import kotlinx.coroutines.flow.first

/** Remembers how the library is sorted, in the tool's DataStore (lightContext.dataStore). */
class LibrarySortPreference(private val dataStore: DataStore<Preferences>) {

    /** The saved choice, or Author A–Z if nothing has been saved yet. */
    suspend fun load(): LibrarySort =
        LibrarySort.fromSavedName(dataStore.data.first()[KEY])

    suspend fun save(sort: LibrarySort) {
        dataStore.edit { prefs -> prefs[KEY] = sort.name }
    }

    private companion object {
        val KEY = stringPreferencesKey("library_sort")
    }
}
