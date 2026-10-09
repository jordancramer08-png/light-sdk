package com.thelightphone.reader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.reader.comics.ComicViewSettings
import com.thelightphone.reader.comics.PanelMargin
import com.thelightphone.reader.comics.PanelTransition
import com.thelightphone.reader.comics.ReadingDirection
import com.thelightphone.reader.comics.SpreadSetting
import kotlinx.coroutines.flow.first

/**
 * Remembers the comic viewer's settings, in the tool's DataStore. Panel margin, Rotate spreads,
 * Reading direction and Spreads are kept per series (the comic's folder: keys
 * `comic_series:<folder>:<setting>`); Panel transition, Crop margins and Auto-advance for every
 * comic. A series with nothing saved yet starts with the margin and Rotate spreads used last,
 * left to right and spreads Auto.
 */
class ComicViewSettingsPreference(private val dataStore: DataStore<Preferences>) {

    /** The settings for a comic in [folder]; anything missing or unknown is that setting's default. */
    suspend fun load(folder: String): ComicViewSettings {
        val prefs = dataStore.data.first()
        return ComicViewSettings(
            margin = PanelMargin.fromSavedName(prefs[seriesKey(folder, "margin")] ?: prefs[MARGIN]),
            transition = PanelTransition.fromSavedName(prefs[TRANSITION]),
            rotateSpreads = prefs[seriesFlag(folder, "rotate_spreads")] ?: prefs[ROTATE_SPREADS] ?: false,
            cropMargins = prefs[CROP_MARGINS] ?: true,
            direction = ReadingDirection.fromSavedName(prefs[seriesKey(folder, "direction")]),
            spreads = SpreadSetting.fromSavedName(prefs[seriesKey(folder, "spreads")]),
            autoAdvance = prefs[AUTO_ADVANCE] ?: false,
        )
    }

    /** Saves the series' settings for [folder], and the rest (and the latest margin and Rotate spreads) for every comic. */
    suspend fun save(folder: String, settings: ComicViewSettings) {
        dataStore.edit { prefs ->
            prefs[seriesKey(folder, "margin")] = settings.margin.name
            prefs[seriesFlag(folder, "rotate_spreads")] = settings.rotateSpreads
            prefs[seriesKey(folder, "direction")] = settings.direction.name
            prefs[seriesKey(folder, "spreads")] = settings.spreads.name
            prefs[MARGIN] = settings.margin.name
            prefs[TRANSITION] = settings.transition.name
            prefs[ROTATE_SPREADS] = settings.rotateSpreads
            prefs[CROP_MARGINS] = settings.cropMargins
            prefs[AUTO_ADVANCE] = settings.autoAdvance
        }
    }

    private fun seriesKey(folder: String, name: String) = stringPreferencesKey(seriesKeyName(folder, name))
    private fun seriesFlag(folder: String, name: String) = booleanPreferencesKey(seriesKeyName(folder, name))

    companion object {
        private val MARGIN = stringPreferencesKey("comic_panel_margin")
        private val TRANSITION = stringPreferencesKey("comic_panel_transition")
        private val ROTATE_SPREADS = booleanPreferencesKey("comic_rotate_spreads")
        private val CROP_MARGINS = booleanPreferencesKey("comic_crop_margins")
        private val AUTO_ADVANCE = booleanPreferencesKey("comic_auto_advance")

        /** Every per-series key starts with this. */
        const val SERIES_PREFIX = "comic_series:"

        fun seriesKeyName(folder: String, name: String) = "$SERIES_PREFIX$folder:$name"
    }
}
