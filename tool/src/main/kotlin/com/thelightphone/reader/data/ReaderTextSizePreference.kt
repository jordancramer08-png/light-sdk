package com.thelightphone.reader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.reader.ReaderTextSize
import kotlinx.coroutines.flow.first

/** Remembers the reading text size for all books, in the tool's DataStore (lightContext.dataStore). */
class ReaderTextSizePreference(private val dataStore: DataStore<Preferences>) {

    /** The saved size, or Medium if nothing has been saved yet. */
    suspend fun load(): ReaderTextSize =
        ReaderTextSize.fromSavedName(dataStore.data.first()[KEY])

    suspend fun save(size: ReaderTextSize) {
        dataStore.edit { prefs -> prefs[KEY] = size.name }
    }

    private companion object {
        val KEY = stringPreferencesKey("reader_text_size")
    }
}
