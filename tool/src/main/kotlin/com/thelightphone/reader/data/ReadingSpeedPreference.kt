package com.thelightphone.reader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import com.thelightphone.reader.DEFAULT_CHARS_PER_MINUTE
import com.thelightphone.reader.updatedSpeed
import kotlinx.coroutines.flow.first

/**
 * Remembers how fast Jordan reads (characters a minute, a rolling average over timed page
 * turns) in the tool's DataStore (lightContext.dataStore). Nothing saved = 1,400.
 */
class ReadingSpeedPreference(private val dataStore: DataStore<Preferences>) {

    suspend fun load(): Float = dataStore.data.first()[KEY]?.takeIf { it > 0f } ?: DEFAULT_CHARS_PER_MINUTE

    /** Adds one timed page to the saved average and returns the new average. */
    suspend fun addPage(pageSpeed: Float): Float {
        var average = DEFAULT_CHARS_PER_MINUTE
        dataStore.edit { prefs ->
            average = updatedSpeed(prefs[KEY]?.takeIf { it > 0f } ?: DEFAULT_CHARS_PER_MINUTE, pageSpeed)
            prefs[KEY] = average
        }
        return average
    }

    private companion object {
        val KEY = floatPreferencesKey("reading_chars_per_minute")
    }
}
