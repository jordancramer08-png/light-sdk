package com.thelightphone.reader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.reader.comics.CleanUp
import kotlinx.coroutines.flow.first

/**
 * Remembers Clean up scans (Off, Auto, Strong) for each comics folder, in the tool's DataStore:
 * key `comic_clean_up:<folder>` ("" for the top folder), the [CleanUp] name. Old scans and
 * clean digital comics usually sit in different folders, so each keeps its own.
 */
class ComicCleanUpPreference(private val dataStore: DataStore<Preferences>) {

    /** The folder's setting; missing or unknown means Auto. */
    suspend fun load(folder: String): CleanUp = CleanUp.fromSavedName(dataStore.data.first()[keyFor(folder)])

    suspend fun save(folder: String, cleanUp: CleanUp) {
        dataStore.edit { it[keyFor(folder)] = cleanUp.name }
    }

    private fun keyFor(folder: String) = stringPreferencesKey("comic_clean_up:$folder")
}
