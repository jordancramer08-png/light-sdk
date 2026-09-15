package com.thelightphone.bible.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.sdk.LightFileShare
import kotlinx.coroutines.flow.first

private val SELECTED_TRANSLATION_KEY = stringPreferencesKey("selected_translation")

/** One installed `bible/<id>/` translation (CLAUDE.md 6), labeled for TranslationScreen. */
data class TranslationOption(val id: String, val displayName: String)

/**
 * Which translations are installed, and which one is active. The active choice is
 * persisted through the SDK's shared DataStore so it survives an app restart; Read and
 * Plan each read it fresh via [selectedTranslation] rather than being told about a change,
 * so picking a translation here applies to both without any wiring between screens
 * (CLAUDE.md 9, TranslationScreen).
 */
class TranslationRepository(
    private val fileShare: LightFileShare,
    private val bibleRepository: BibleRepository,
    private val dataStore: DataStore<Preferences>,
) {

    /** Every `bible/<id>/` folder with a readable manifest, labeled with its display name. */
    fun availableTranslations(): List<TranslationOption> =
        fileShare.list(ROOT_DIR)
            .sorted()
            .mapNotNull { id -> bibleRepository.loadManifest(id)?.let { TranslationOption(id, it.displayName) } }

    suspend fun selectedTranslation(): String =
        dataStore.data.first()[SELECTED_TRANSLATION_KEY] ?: DEFAULT_TRANSLATION

    suspend fun selectTranslation(id: String) {
        dataStore.edit { it[SELECTED_TRANSLATION_KEY] = id }
    }

    private companion object {
        const val ROOT_DIR = "bible"
    }
}
