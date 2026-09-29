package com.thelightphone.reader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.reader.LibrarySection
import kotlinx.coroutines.flow.first

/** Remembers whether the Library last showed Books or Comics, in the tool's DataStore. */
class LibrarySectionPreference(private val dataStore: DataStore<Preferences>) {

    /** The saved section, or Books if nothing (or something unknown) was saved. */
    suspend fun load(): LibrarySection = LibrarySection.fromSavedName(dataStore.data.first()[KEY])

    suspend fun save(section: LibrarySection) {
        dataStore.edit { prefs -> prefs[KEY] = section.name }
    }

    private companion object {
        val KEY = stringPreferencesKey("library_section")
    }
}
