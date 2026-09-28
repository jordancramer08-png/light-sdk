package com.thelightphone.reader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.reader.ReaderLineSpacing
import com.thelightphone.reader.ReaderMargins
import com.thelightphone.reader.ReaderSettings
import com.thelightphone.reader.ReaderTextSize
import com.thelightphone.reader.ReaderTypeface
import kotlinx.coroutines.flow.first

/**
 * Remembers the reading settings (size, typeface, line spacing, margins) for all books, in
 * the tool's DataStore (lightContext.dataStore). Each is stored by its enum name; anything
 * missing or unknown comes back as that setting's default.
 */
class ReaderSettingsPreference(private val dataStore: DataStore<Preferences>) {

    suspend fun load(): ReaderSettings {
        val prefs = dataStore.data.first()
        return ReaderSettings(
            textSize = ReaderTextSize.fromSavedName(prefs[TEXT_SIZE]),
            typeface = ReaderTypeface.fromSavedName(prefs[TYPEFACE]),
            lineSpacing = ReaderLineSpacing.fromSavedName(prefs[LINE_SPACING]),
            margins = ReaderMargins.fromSavedName(prefs[MARGINS]),
        )
    }

    suspend fun save(settings: ReaderSettings) {
        dataStore.edit { prefs ->
            prefs[TEXT_SIZE] = settings.textSize.name
            prefs[TYPEFACE] = settings.typeface.name
            prefs[LINE_SPACING] = settings.lineSpacing.name
            prefs[MARGINS] = settings.margins.name
        }
    }

    private companion object {
        // Same key the text size has always used, so a saved size carries over.
        val TEXT_SIZE = stringPreferencesKey("reader_text_size")
        val TYPEFACE = stringPreferencesKey("reader_typeface")
        val LINE_SPACING = stringPreferencesKey("reader_line_spacing")
        val MARGINS = stringPreferencesKey("reader_margins")
    }
}
