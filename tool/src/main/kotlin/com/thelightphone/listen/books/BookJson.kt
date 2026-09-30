package com.thelightphone.listen.books

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One entry of book.json's "files": the file [name] (relative to the book folder) and its [label]. */
data class BookJsonFile(val name: String, val label: String?, val chapters: List<ChapterMark>)

/**
 * What book.json says, read field by field, so one odd value (a number written as text, a
 * null, a missing field) never loses the rest. Blank text reads as null.
 */
data class BookJson(
    val id: String? = null,
    val title: String? = null,
    val author: String? = null,
    val series: String? = null,
    val seriesNumber: Double? = null,
    val narrator: String? = null,
    val year: String? = null,
    val cover: String? = null,
    val files: List<BookJsonFile> = emptyList(),
)

private val lenient = Json { isLenient = true }

/** book.json's contents, or null when the text isn't a JSON object at all. Never throws. */
fun parseBookJson(text: String): BookJson? {
    val root = try {
        // PowerShell often starts UTF-8 files with a byte-order mark.
        lenient.parseToJsonElement(text.removePrefix("﻿")) as? JsonObject
    } catch (e: Exception) {
        null
    } ?: return null
    return BookJson(
        id = root.text("id"),
        title = root.text("title"),
        author = root.text("author"),
        series = root.text("series"),
        seriesNumber = root.text("seriesNumber")?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() },
        narrator = root.text("narrator"),
        year = root.text("year"),
        cover = root.text("cover")?.let(::cleanPath),
        files = (root["files"] as? JsonArray).orEmpty().mapNotNull(::fileEntry),
    )
}

private fun fileEntry(element: JsonElement): BookJsonFile? {
    val obj = element as? JsonObject ?: return null
    val name = obj.text("name")?.let(::cleanPath) ?: return null
    val chapters = (obj["chapters"] as? JsonArray).orEmpty().mapNotNull { chapter ->
        val mark = chapter as? JsonObject ?: return@mapNotNull null
        val start = mark.text("startMs")?.toDoubleOrNull()?.toLong() ?: return@mapNotNull null
        ChapterMark(title = mark.text("title").orEmpty(), startMs = start.coerceAtLeast(0))
    }.sortedBy { it.startMs }
    return BookJsonFile(name = name, label = obj.text("label"), chapters = chapters)
}

/** A field as trimmed text (numbers and true/false included), or null when missing, null or blank. */
private fun JsonObject.text(key: String): String? {
    val value = this[key] as? JsonPrimitive ?: return null
    if (value is JsonNull) return null
    return value.content.trim().takeIf { it.isNotEmpty() }
}

/**
 * "CD1\01.mp3" → "CD1/01.mp3"; no leading "./" or "/". A path with ".." is refused, so
 * nothing outside the book folder is ever read.
 */
private fun cleanPath(path: String): String? {
    val clean = path.replace('\\', '/').trimStart('/').removePrefix("./")
    if (clean.isBlank() || clean.split('/').any { it == ".." }) return null
    return clean
}
