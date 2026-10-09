package com.thelightphone.reader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.thelightphone.reader.comics.comicSlug
import com.thelightphone.reader.comics.forgetRequests
import com.thelightphone.reader.comics.isSeriesKeyOf
import java.io.File

/**
 * "Also forget my place and settings" when comics are removed (CLAUDE.md 12): each comic's saved
 * place (`comic_position`) and its hand-set pages, and for a removed folder the series settings
 * of it and every folder under it (mode, margin, direction, spreads, Rotate spreads, Clean up
 * scans). Reading status and lists are kept.
 *
 * The PC's remove script can't reach the database, so it writes what to forget into
 * [PENDING_FILE] (`files/shared/comics-forget.txt`) and the app forgets it on its next launch
 * ([forgetPending]).
 */
class ComicForgetting(
    private val dataStore: DataStore<Preferences>,
    private val positionRepository: ComicPositionRepository,
    private val filesDir: File,
) {

    /** Forgets [comics] (paths) and the series settings of [folders] (paths) and the folders under them. */
    suspend fun forget(comics: List<String>, folders: List<String>) {
        DatabaseQueue.read { comics.forEach { positionRepository.delete(comicSlug(it)) } }
        val pageKeys = comics.map { ComicPageOverridePreference.PREFIX + it }.toSet()
        dataStore.edit { prefs ->
            val gone = prefs.asMap().keys.filter { key ->
                key.name in pageKeys || folders.any { isSeriesKeyOf(key.name, it) }
            }
            gone.forEach { prefs.remove(it) }
        }
    }

    /** Forgets what the remove script asked for since the last launch, then deletes its note. False when there was nothing. */
    suspend fun forgetPending(): Boolean {
        val file = File(filesDir, PENDING_FILE)
        if (!file.isFile) return false
        val requests = forgetRequests(file.readText())
        forget(requests.comics, requests.folders)
        file.delete()
        return true
    }

    companion object {
        /** Written by the PC's remove script, under the app's files folder. */
        const val PENDING_FILE = "shared/comics-forget.txt"
    }
}
