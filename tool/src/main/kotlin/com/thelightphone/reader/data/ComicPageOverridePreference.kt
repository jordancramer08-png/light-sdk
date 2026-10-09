package com.thelightphone.reader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.thelightphone.reader.comics.PageOverrides
import kotlinx.coroutines.flow.first

/**
 * Remembers what the reader set by hand on a comic's pages (this page is / isn't a spread,
 * show this page whole), in the tool's DataStore: key `comic_pages:<comic path>`, a set of
 * strings ([PageOverrides.toSaved]).
 */
class ComicPageOverridePreference(private val dataStore: DataStore<Preferences>) {

    suspend fun load(path: String): PageOverrides = PageOverrides.fromSaved(dataStore.data.first()[keyFor(path)])

    suspend fun save(path: String, overrides: PageOverrides) {
        dataStore.edit { prefs ->
            val saved = overrides.toSaved()
            if (saved.isEmpty()) prefs.remove(keyFor(path)) else prefs[keyFor(path)] = saved
        }
    }

    companion object {
        const val PREFIX = "comic_pages:"
        private fun keyFor(path: String) = stringSetPreferencesKey(PREFIX + path)
    }
}
