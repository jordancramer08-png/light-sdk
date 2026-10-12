package com.jordan.watch.logic

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One movie, from movie.json (written by the PC script). Only id and title are certain. */
@Serializable
data class Movie(
    val schema: Int = 1,
    val id: String,
    val title: String,
    val year: Int? = null,
    val collection: String? = null,
    val collectionNumber: Int? = null,
    val overview: String = "",
    val genres: List<String> = emptyList(),
    val runtimeMin: Int? = null,
    val rating: Double? = null,
    val contentRating: String? = null,
    val director: String? = null,
    val cast: List<String> = emptyList(),
    val poster: String? = "poster.jpg",
    val backdrop: String? = null,
    val file: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val bytes: Long? = null,
    val sentAt: String? = null,
)

/** One TV show, from show.json. */
@Serializable
data class Show(
    val schema: Int = 1,
    val id: String,
    val title: String,
    val year: Int? = null,
    val overview: String = "",
    val genres: List<String> = emptyList(),
    val contentRating: String? = null,
    val rating: Double? = null,
    val cast: List<String> = emptyList(),
    val poster: String? = "poster.jpg",
)

/** One episode, from S01E01.json. */
@Serializable
data class Episode(
    val schema: Int = 1,
    val id: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val overview: String = "",
    val runtimeMin: Int? = null,
    val airDate: String? = null,
    val still: String? = null,
    val file: String? = null,
)

/**
 * Reads the json files the PC script writes. A missing or broken file never crashes:
 * the title falls back to what the folder or file name says.
 */
object MediaJson {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val titleYear = Regex("""^(.*?)\s*\((\d{4})\)$""")
    private val episodeCode = Regex("""^S(\d{1,3})E(\d{1,4})$""", RegexOption.IGNORE_CASE)

    /** [folder] is `.../Movies/<Collection>/<Title (Year)>`; [id] is "Collection/Title (Year)". */
    fun readMovie(folder: File, id: String): Movie {
        val text = AtomicFile.readTextOrNull(File(folder, "movie.json"))
        parseOrNull(text, Movie.serializer())?.let { return it }
        val (title, year) = splitTitleYear(folder.name)
        val collection = folder.parentFile?.name?.takeIf { it != "_Singles" }
        return Movie(id = id, title = title, year = year, collection = collection)
    }

    /** [folder] is `.../TV/<Show>`. */
    fun readShow(folder: File): Show {
        val text = AtomicFile.readTextOrNull(File(folder, "show.json"))
        parseOrNull(text, Show.serializer())?.let { return it }
        val (title, year) = splitTitleYear(folder.name)
        return Show(id = folder.name, title = title, year = year)
    }

    /** [jsonFile] is `.../Season 01/S01E01.json`; it may not exist. */
    fun readEpisode(jsonFile: File, showId: String): Episode {
        val text = AtomicFile.readTextOrNull(jsonFile)
        parseOrNull(text, Episode.serializer())?.let { return it }
        val code = jsonFile.nameWithoutExtension
        val match = episodeCode.matchEntire(code)
        val season = match?.groupValues?.get(1)?.toInt() ?: 0
        val episode = match?.groupValues?.get(2)?.toInt() ?: 0
        return Episode(id = "$showId/$code", title = "Episode $episode", season = season, episode = episode)
    }

    /** The "file" movie.json names, or null when the json is missing, broken or names none. */
    fun videoFileName(jsonFile: File): String? =
        parseOrNull(AtomicFile.readTextOrNull(jsonFile), Movie.serializer())?.file?.takeIf { it.isNotBlank() }

    /** "Hacksaw Ridge (2016)" ->("Hacksaw Ridge", 2016); no year -> (name, null). */
    fun splitTitleYear(name: String): Pair<String, Int?> {
        val trimmed = name.trim()
        val match = titleYear.matchEntire(trimmed) ?: return trimmed to null
        return match.groupValues[1] to match.groupValues[2].toInt()
    }

    private fun <T> parseOrNull(text: String?, serializer: KSerializer<T>): T? {
        if (text == null) return null
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: Exception) {
            null
        }
    }
}
