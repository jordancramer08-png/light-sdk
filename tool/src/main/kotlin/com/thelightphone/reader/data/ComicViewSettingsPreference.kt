package com.thelightphone.reader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.reader.comics.ComicViewSettings
import com.thelightphone.reader.comics.PanelMargin
import com.thelightphone.reader.comics.PanelTransition
import kotlinx.coroutines.flow.first

/** Remembers the comic viewer's settings (panel margin, panel transition, Rotate spreads, Crop margins), in the tool's DataStore. */
class ComicViewSettingsPreference(private val dataStore: DataStore<Preferences>) {

    /** The saved settings; anything missing or unknown is that setting's default. */
    suspend fun load(): ComicViewSettings {
        val prefs = dataStore.data.first()
        return ComicViewSettings(
            margin = PanelMargin.fromSavedName(prefs[MARGIN]),
            transition = PanelTransition.fromSavedName(prefs[TRANSITION]),
            rotateSpreads = prefs[ROTATE_SPREADS] ?: false,
            cropMargins = prefs[CROP_MARGINS] ?: true,
        )
    }

    suspend fun save(settings: ComicViewSettings) {
        dataStore.edit { prefs ->
            prefs[MARGIN] = settings.margin.name
            prefs[TRANSITION] = settings.transition.name
            prefs[ROTATE_SPREADS] = settings.rotateSpreads
            prefs[CROP_MARGINS] = settings.cropMargins
        }
    }

    private companion object {
        val MARGIN = stringPreferencesKey("comic_panel_margin")
        val TRANSITION = stringPreferencesKey("comic_panel_transition")
        val ROTATE_SPREADS = booleanPreferencesKey("comic_rotate_spreads")
        val CROP_MARGINS = booleanPreferencesKey("comic_crop_margins")
    }
}
