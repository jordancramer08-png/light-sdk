package com.thelightphone.sample.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One-time JSON seed import (build order phase 6).
 *
 * On launch, if a file named [FILE_NAME] exists in the app's shared file
 * directory (`lightContext.fileShare`), its groups and people are added to the
 * database and the file is renamed to [archivedName] so it never imports
 * again. Only groups and people are imported - not requests, praises or
 * updates.
 *
 * Expected JSON shape (see `docs/prayer_seed.example.json`):
 * ```
 * {
 *   "groups": [
 *     { "name": "Family", "people": [ { "name": "Aunt Sue", "note": "..." } ] }
 *   ],
 *   "ungrouped": [ { "name": "Kim next door" } ]
 * }
 * ```
 */
object SeedImport {

    /** The file the user drops into the shared directory. */
    const val FILE_NAME = "prayer_seed.json"

    private val json = Json {
        ignoreUnknownKeys = true // lets the example file keep its "_comment" key
        isLenient = true
    }

    /** Parses seed JSON. Throws if the text is not valid JSON of the right shape. */
    fun parse(text: String): SeedFile = json.decodeFromString(SeedFile.serializer(), text)

    /** What the file is renamed to once imported, so the next launch skips it. */
    fun archivedName(now: Long): String = "prayer_seed.imported-$now.json"
}

@Serializable
data class SeedFile(
    val groups: List<SeedGroup> = emptyList(),
    val ungrouped: List<SeedPerson> = emptyList(),
)

@Serializable
data class SeedGroup(
    val name: String,
    val people: List<SeedPerson> = emptyList(),
)

@Serializable
data class SeedPerson(
    val name: String,
    val note: String? = null,
)
