package com.thelightphone.reader.comics

import com.thelightphone.reader.epub.allText
import com.thelightphone.reader.epub.firstChild
import com.thelightphone.reader.epub.firstDescendant
import com.thelightphone.reader.epub.parseXml
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What Comic Details shows about a comic (CLAUDE.md 12, "Comic details"), in plain Kotlin so
 * it can be unit-tested on the PC. No Android here.
 *
 * Each value is taken from the best place that has it, in this order: the details file
 * beside the comic ([DetailsSidecar]), the CBZ's ComicInfo.xml, the file name, and last the
 * pages counted in the CBZ (page count only).
 */

/** Where a value came from, best first. */
enum class DetailsSource(val label: String) {
    SIDECAR("the details file"),
    COMIC_INFO("ComicInfo.xml"),
    FILE_NAME("the file name"),
    PAGES("the pages counted"),
}

/** The values of [ComicDetails], each with its own [DetailsSource]. The year and month travel together as DATE. */
enum class ComicField { SERIES, ISSUE, VOLUME, TITLE, DATE, WRITER, ARTISTS, PUBLISHER, SUMMARY, PAGE_COUNT, NOTES }

/**
 * One comic's details. Everything may be missing. [issue] and [volume] are text ("1", "1.5",
 * "2011"). [month] is 1–12. [notes] are the extra bits in brackets in the file name ("First
 * appearance of Superman, Lois Lane"). [source] says where each value came from.
 */
data class ComicDetails(
    val series: String? = null,
    val issue: String? = null,
    val volume: String? = null,
    val title: String? = null,
    val year: Int? = null,
    val month: Int? = null,
    val writer: String? = null,
    val artists: List<String> = emptyList(),
    val publisher: String? = null,
    val summary: String? = null,
    val pageCount: Int? = null,
    val notes: List<String> = emptyList(),
    val source: Map<ComicField, DetailsSource> = emptyMap(),
) {
    /** True when this comic has a value for [field]. */
    fun has(field: ComicField): Boolean = when (field) {
        ComicField.SERIES -> series != null
        ComicField.ISSUE -> issue != null
        ComicField.VOLUME -> volume != null
        ComicField.TITLE -> title != null
        ComicField.DATE -> year != null
        ComicField.WRITER -> writer != null
        ComicField.ARTISTS -> artists.isNotEmpty()
        ComicField.PUBLISHER -> publisher != null
        ComicField.SUMMARY -> summary != null
        ComicField.PAGE_COUNT -> pageCount != null
        ComicField.NOTES -> notes.isNotEmpty()
    }

    /** These details with [field] copied from [other]. */
    private fun withValue(field: ComicField, other: ComicDetails): ComicDetails = when (field) {
        ComicField.SERIES -> copy(series = other.series)
        ComicField.ISSUE -> copy(issue = other.issue)
        ComicField.VOLUME -> copy(volume = other.volume)
        ComicField.TITLE -> copy(title = other.title)
        ComicField.DATE -> copy(year = other.year, month = other.month)
        ComicField.WRITER -> copy(writer = other.writer)
        ComicField.ARTISTS -> copy(artists = other.artists)
        ComicField.PUBLISHER -> copy(publisher = other.publisher)
        ComicField.SUMMARY -> copy(summary = other.summary)
        ComicField.PAGE_COUNT -> copy(pageCount = other.pageCount)
        ComicField.NOTES -> copy(notes = other.notes)
    }

    /** These details with every value they have marked as coming from [from]. */
    fun from(from: DetailsSource): ComicDetails =
        copy(source = ComicField.entries.filter(::has).associateWith { from })

    /** [field] taken from [other], with its source. */
    fun taking(field: ComicField, other: ComicDetails): ComicDetails {
        val source = other.source[field] ?: return withValue(field, other)
        return withValue(field, other).copy(source = this.source + (field to source))
    }
}

/** Each value from the first of [parts] (best first) that has it. */
fun combineDetails(parts: List<ComicDetails>): ComicDetails =
    ComicField.entries.fold(ComicDetails()) { combined, field ->
        val best = parts.firstOrNull { it.has(field) }
        if (best == null) combined else combined.taking(field, best)
    }

/**
 * A comic's details from everything known about it, best first: the details file's text
 * ([sidecarJson], null when there is none), the ComicInfo.xml text ([comicInfoXml]), the
 * comic's [fileName], and its [pageCount] counted in the CBZ.
 */
fun comicDetails(sidecarJson: String?, comicInfoXml: String?, fileName: String, pageCount: Int): ComicDetails =
    combineDetails(
        listOfNotNull(
            sidecarJson?.let(::parseDetailsSidecar),
            comicInfoXml?.let(::parseComicInfo),
            parseComicFileName(fileName),
            ComicDetails(pageCount = pageCount.takeIf { it > 0 }).from(DetailsSource.PAGES),
        ),
    )

// --- the details file (sidecar) ----------------------------------------------------------

/**
 * The details file: `<comic name>.details.json` beside the comic (for
 * "00001. Action Comics #1 (1938).cbz", "00001. Action Comics #1 (1938).details.json").
 * Written by a future PC-side lookup; every value is optional, and unknown keys are ignored.
 * Its format is in CLAUDE.md 12.
 */
@Serializable
data class DetailsSidecar(
    val version: Int = 1,
    val series: String? = null,
    val issue: String? = null,
    val volume: String? = null,
    val title: String? = null,
    val year: Int? = null,
    val month: Int? = null,
    val writer: String? = null,
    val artists: List<String> = emptyList(),
    val publisher: String? = null,
    val summary: String? = null,
    val pageCount: Int? = null,
    val notes: List<String> = emptyList(),
)

private val sidecarJson = Json { ignoreUnknownKeys = true }

/** The details file's name for a comic file: "Name.cbz" -> "Name.details.json". */
fun detailsSidecarName(comicFileName: String): String {
    val base = if (comicFileName.endsWith(".cbz", ignoreCase = true)) comicFileName.dropLast(4) else comicFileName
    return "$base.details.json"
}

/** The details file's values, or null when it isn't readable JSON. Blank values count as missing. */
fun parseDetailsSidecar(json: String): ComicDetails? {
    val sidecar = try {
        sidecarJson.decodeFromString(DetailsSidecar.serializer(), json.removePrefix("﻿"))
    } catch (e: IllegalArgumentException) { // includes SerializationException
        return null
    }
    return ComicDetails(
        series = tidy(sidecar.series),
        issue = tidy(sidecar.issue)?.let(::tidyIssue),
        volume = tidy(sidecar.volume),
        title = tidy(sidecar.title),
        year = sidecar.year?.takeIf(::isYear),
        month = sidecar.month?.takeIf { sidecar.year != null && it in 1..12 },
        writer = tidy(sidecar.writer),
        artists = tidyNames(sidecar.artists),
        publisher = tidy(sidecar.publisher),
        summary = tidy(sidecar.summary),
        pageCount = sidecar.pageCount?.takeIf { it > 0 },
        notes = sidecar.notes.mapNotNull(::tidy),
    ).from(DetailsSource.SIDECAR)
}

// --- ComicInfo.xml ---------------------------------------------------------------------------

/**
 * The values in a ComicInfo.xml (the ComicRack format: `<Series>`, `<Number>`, `<Volume>`,
 * `<Title>`, `<Year>`, `<Month>`, `<Writer>`, `<Penciller>`, `<Inker>`, `<Colorist>`,
 * `<Publisher>`, `<Summary>`, `<PageCount>`). -1 ("unknown") and blanks count as missing.
 * Artists are the penciller, inker and colorist, each name once.
 */
fun parseComicInfo(xml: String): ComicDetails {
    // The markup reader lowercases tag names, so they are looked up that way.
    val root = firstDescendant(parseXml(xml.removePrefix("﻿")), "comicinfo") ?: return ComicDetails()
    fun text(tag: String): String? = firstChild(root, tag.lowercase())?.let { tidy(allText(it)) }
    fun number(tag: String): Int? = text(tag)?.toIntOrNull()
    val year = number("Year")?.takeIf(::isYear)
    return ComicDetails(
        series = text("Series"),
        issue = text("Number")?.let(::tidyIssue),
        volume = text("Volume")?.takeIf { it != "-1" && it != "0" },
        title = text("Title"),
        year = year,
        month = number("Month")?.takeIf { year != null && it in 1..12 },
        writer = text("Writer"),
        artists = tidyNames(listOf("Penciller", "Inker", "Colorist").flatMap { text(it)?.split(',').orEmpty() }),
        publisher = text("Publisher"),
        summary = text("Summary"),
        pageCount = number("PageCount")?.takeIf { it > 0 },
    ).from(DetailsSource.COMIC_INFO)
}

/** The ComicInfo.xml among a CBZ's files: at the top first, else in any folder (not a Mac leftover). */
fun comicInfoEntry(entryNames: List<String>): String? {
    fun isComicInfo(name: String) = name.substringAfterLast('/').equals(COMIC_INFO_NAME, ignoreCase = true)
    return entryNames.firstOrNull { it.equals(COMIC_INFO_NAME, ignoreCase = true) }
        ?: entryNames.firstOrNull { isComicInfo(it) && !it.contains("__MACOSX", ignoreCase = true) }
}

private const val COMIC_INFO_NAME = "ComicInfo.xml"

// --- the file name ---------------------------------------------------------------------------

/** "Series #12", "Series #12.5", "Series #0", "Series #1 - Story title", "Series #1 Director's Cut". */
private val ISSUE_WITH_HASH = Regex("""^(.*?)\s*#\s*(\d+(?:\.\d+)?[A-Za-z]?|½)(?:\s+(?:-\s+)?(.+))?$""")

/** "Crisis on Infinite Earths 01": a number of 1–3 digits after a name, no "#". */
private val ISSUE_AT_END = Regex("""^(.*[A-Za-z].*?)\s+(\d{1,3}(?:\.\d+)?)$""")

/** "Batman v2", "Batman Vol. 2", "Batman, Volume 2" at the end of the series. */
private val VOLUME_AT_END = Regex("""^(.+?),?\s+(?:v|vol\.?|volume)\s*(\d+)$""", RegexOption.IGNORE_CASE)

/** A bracket that is only a volume: "(Vol. 2)", "(v2)". */
private val VOLUME_GROUP = Regex("""^(?:v|vol\.?|volume)\s*(\d+)$""", RegexOption.IGNORE_CASE)

/** "(1938)", "(June 1938)", "(Jun. 1938)", "(1938-06)", "(1940-41)", "(1985-1986)". */
private val DATE_GROUP = Regex("""^(?:([A-Za-z]+)\.?\s+)?(\d{4})(?:\s*[-/]\s*(\d{1,4}))?$""")

/** "(of 12)": how many issues the series has. */
private val ISSUE_COUNT_GROUP = Regex("""^of\s+(\d+)$""", RegexOption.IGNORE_CASE)

/** Brackets that only say who scanned it or how, not worth showing. */
private val SCAN_TAGS = setOf("digital", "c2c", "webrip", "scan", "hd-upscaled")

private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

/**
 * What a comic's file name says: "00001. Action Comics #1 (1938) (First appearance of
 * Superman, Lois Lane).cbz" -> series "Action Comics", issue "1", year 1938, notes ["First
 * appearance of Superman, Lois Lane"]. The reading-order number and the extension are
 * dropped ([cleanComicTitle]); brackets at the end give the date, a volume, "of 12", and
 * notes; square brackets are left out (scanners' tags).
 */
fun parseComicFileName(fileName: String): ComicDetails {
    var head = cleanComicTitle(fileName)
    val groups = mutableListOf<String>()
    while (true) {
        val (rest, group) = splitLastGroup(head) ?: break
        head = rest.trim()
        if (group.startsWith("(")) groups.add(0, group.drop(1).dropLast(1).trim())
    }
    return namePart(head).withGroups(groups).from(DetailsSource.FILE_NAME)
}

/** Series, issue, story title and volume from the name without its brackets. */
private fun namePart(head: String): ComicDetails {
    val withHash = ISSUE_WITH_HASH.find(head)
    val atEnd = if (withHash == null) ISSUE_AT_END.find(head) else null
    val series = withHash?.groupValues?.get(1) ?: atEnd?.groupValues?.get(1) ?: head
    val issue = (withHash ?: atEnd)?.groupValues?.get(2)?.let(::tidyIssue)
    val title = withHash?.groupValues?.get(3)?.let(::tidy)
    val volume = VOLUME_AT_END.find(series.trim())
    return ComicDetails(
        series = tidy(volume?.groupValues?.get(1) ?: series),
        issue = issue,
        volume = volume?.groupValues?.get(2)?.let(::tidyIssue),
        title = title,
    )
}

/** The brackets' meaning: the first date is the date, a volume the volume, "of 12" and the rest are notes. */
private fun ComicDetails.withGroups(groups: List<String>): ComicDetails {
    var details = this
    val notes = mutableListOf<String>()
    for (group in groups) {
        val date = if (details.year == null) parseDateGroup(group) else null
        val volume = VOLUME_GROUP.find(group)
        val count = ISSUE_COUNT_GROUP.find(group)
        when {
            date != null -> details = details.copy(year = date.first, month = date.second)
            volume != null && details.volume == null -> details = details.copy(volume = tidyIssue(volume.groupValues[1]))
            count != null -> notes.add(issueCountNote(details.issue, count.groupValues[1]))
            group.isNotEmpty() && group.lowercase() !in SCAN_TAGS -> notes.add(group)
        }
    }
    return details.copy(notes = notes)
}

/** "Issue 1 of 12", or "One of 12 issues" when the issue isn't known. */
private fun issueCountNote(issue: String?, count: String): String {
    val total = count.trimStart('0').ifEmpty { "0" }
    return if (issue != null) "Issue $issue of $total" else "One of $total issues"
}

/** (year, month or null) from a bracket's text, or null when it isn't a date. */
fun parseDateGroup(text: String): Pair<Int, Int?>? {
    val m = DATE_GROUP.find(text.trim()) ?: return null
    val year = m.groupValues[2].toInt().takeIf(::isYear) ?: return null
    val word = m.groupValues[1]
    val tail = m.groupValues[3]
    val month = when {
        word.isNotEmpty() -> monthNumber(word)
        tail.length <= 2 && tail.isNotEmpty() -> tail.toInt().takeIf { it in 1..12 }
        else -> null
    }
    return year to month
}

/** 1–12 for a month's name or its short form ("June", "Jun", "Sept"); null for anything else ("Spring"). */
private fun monthNumber(word: String): Int? {
    if (word.length < 3) return null
    val index = MONTHS.indexOf(word.take(3).lowercase())
    return if (index >= 0) index + 1 else null
}

/**
 * The last bracket at the end of [text], matched even when brackets nest: "A (b (c))" ->
 * ("A ", "(b (c))"). Null when [text] doesn't end with ")" or "]", or its bracket never opens.
 */
private fun splitLastGroup(text: String): Pair<String, String>? {
    val trimmed = text.trimEnd()
    val close = trimmed.lastOrNull() ?: return null
    val open = when (close) {
        ')' -> '('
        ']' -> '['
        else -> return null
    }
    var depth = 0
    for (i in trimmed.indices.reversed()) {
        if (trimmed[i] == close) depth++
        if (trimmed[i] == open) depth--
        if (depth == 0) return trimmed.substring(0, i) to trimmed.substring(i)
    }
    return null
}

// --- tidying ---------------------------------------------------------------------------------

/** Trimmed, whitespace runs made one space; null when nothing is left. */
private fun tidy(text: String?): String? =
    text?.trim()?.replace(Regex("""\s+"""), " ")?.takeIf { it.isNotEmpty() }

/** An issue number without its leading zeros: "001" -> "1", "000" -> "0", "01.5" -> "1.5". */
private fun tidyIssue(issue: String): String {
    val trimmed = issue.trim()
    val digits = trimmed.takeWhile { it.isDigit() }
    if (digits.isEmpty()) return trimmed
    return digits.trimStart('0').ifEmpty { "0" } + trimmed.drop(digits.length)
}

/** Names tidied, each once (ignoring case), in the order given. */
private fun tidyNames(names: List<String>): List<String> =
    names.mapNotNull(::tidy).distinctBy { it.lowercase() }

private fun isYear(year: Int): Boolean = year in 1800..2099
