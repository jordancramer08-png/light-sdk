package com.thelightphone.sample.data

import com.thelightphone.sdk.LightFileShare
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

sealed interface StudyContentResult {
    data class Loaded(val content: StudyContent) : StudyContentResult

    /** [StudyContentRepository.FILE_NAME] hasn't been dropped into the shared directory yet. */
    data object NotFound : StudyContentResult

    /** The file exists but isn't valid JSON, or doesn't match the expected shape. */
    data class Invalid(val message: String) : StudyContentResult
}

/**
 * Loads the study content file from the app's shared directory
 * (`lightContext.fileShare`). It is copyrighted study material, is never
 * bundled in the APK, and is never committed to the repo (CLAUDE.md 4) - it
 * lives only where the user drops it on-device, the same shared directory
 * the prayer-list project used for its JSON seed
 * (`docs/prayer_seed.example.json`, `SeedFileImporter`).
 *
 * Unlike that seed import, this is read-only: the content file is read every
 * time, never renamed or deleted, since the app needs it for as long as it's
 * installed.
 *
 * Blocking; call from a background coroutine. The parsed content is cached
 * in memory after the first successful load.
 */
class StudyContentRepository(private val fileShare: LightFileShare) {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: StudyContent? = null

    fun load(): StudyContentResult {
        cached?.let { return StudyContentResult.Loaded(it) }

        val text = fileShare.read(FILE_NAME) { it.readText() }
            ?: return StudyContentResult.NotFound

        return try {
            val content = json.decodeFromString(StudyContent.serializer(), text)
            cached = content
            StudyContentResult.Loaded(content)
        } catch (e: SerializationException) {
            StudyContentResult.Invalid(e.message ?: "Malformed content file")
        } catch (e: IllegalArgumentException) {
            StudyContentResult.Invalid(e.message ?: "Malformed content file")
        }
    }

    companion object {
        /** The file the user drops into the shared directory (see NOTES.md 4). */
        const val FILE_NAME = "if_this_is_the_end.json"
    }
}
