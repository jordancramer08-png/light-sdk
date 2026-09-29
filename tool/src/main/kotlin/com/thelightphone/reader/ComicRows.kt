package com.thelightphone.reader

import com.thelightphone.reader.comics.ComicFolderItem
import com.thelightphone.reader.comics.ComicItemKind
import com.thelightphone.reader.comics.cleanComicTitle
import com.thelightphone.reader.comics.comicPathOf
import com.thelightphone.reader.comics.comicSlug
import com.thelightphone.reader.comics.parentPath
import com.thelightphone.reader.data.ComicMeta
import com.thelightphone.reader.data.ComicPosition
import java.text.NumberFormat
import java.time.ZoneId
import java.util.Locale

/**
 * What the Comics section shows, worked out in plain Kotlin so it can be unit-tested on the
 * PC (CLAUDE.md 12). No Android or Compose here.
 */

/** The two halves of the Library, switched with the bar at the bottom. */
enum class LibrarySection {
    BOOKS,
    COMICS;

    companion object {
        val DEFAULT = BOOKS

        /** Turns a saved name back into a section; anything unknown means Books. */
        fun fromSavedName(name: String?): LibrarySection = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** One row in a comics folder (or a list's comics). */
sealed interface ComicEntry {
    val path: String
    val title: String

    /** A subfolder; [summary] is "3 folders · 12 comics". */
    data class Folder(override val path: String, override val title: String, val summary: String) : ComicEntry

    /** A comic. [meta] is null until the CBZ has been read the first time. */
    data class Comic(
        override val path: String,
        override val title: String,
        val meta: ComicMeta?,
        val position: ComicPosition?,
        val status: ReadingStatus,
    ) : ComicEntry {
        val slug: String get() = comicSlug(path)
        val canOpen: Boolean get() = meta?.canOpen == true
        val pagesText: String get() = comicPagesText(meta)
        val progressText: String get() = comicProgressText(meta, position, status)

        /** True when the row shows "Page N of M" or "Finished" (drawn in the theme's accent). */
        val isStarted: Boolean get() = canOpen && (position != null || status == ReadingStatus.FINISHED)
    }

    /** A note file (.txt) sitting beside the comics. */
    data class Note(override val path: String, override val title: String) : ComicEntry
}

/** A comic's row from what's known about it now. */
fun comicEntry(
    path: String,
    meta: ComicMeta?,
    positions: Map<String, ComicPosition>,
    statuses: Map<String, ReadingStatus>,
): ComicEntry.Comic {
    val slug = comicSlug(path)
    return ComicEntry.Comic(
        path = path,
        title = cleanComicTitle(path.substringAfterLast('/')),
        meta = meta,
        position = positions[slug],
        status = statuses[slug] ?: ReadingStatus.DEFAULT,
    )
}

/** The comic's page count line: "30 pages"; "Preparing…" until it's been read; "Can't open" for a bad file. */
fun comicPagesText(meta: ComicMeta?): String = when {
    meta == null -> "Preparing…"
    meta.problem -> "Can't open"
    meta.pageCount == 1 -> "1 page"
    else -> "${meta.pageCount} pages"
}

/** "Not started", "Page 12 of 30" or "Finished". */
fun comicProgressText(meta: ComicMeta?, position: ComicPosition?, status: ReadingStatus): String = when {
    meta?.problem == true -> ""
    status == ReadingStatus.FINISHED -> ReadingStatus.FINISHED.label
    position == null -> "Not started"
    meta == null -> "Page ${position.page}"
    else -> "Page ${position.page.coerceIn(1, meta.pageCount.coerceAtLeast(1))} of ${meta.pageCount}"
}

/** The comic that was opened last: the latest saved place among [positions] whose comic [exists]. */
fun lastOpenedComicPath(positions: Collection<ComicPosition>, exists: (String) -> Boolean): String? =
    positions
        .sortedByDescending { it.updatedAt }
        .firstNotNullOfOrNull { position -> comicPathOf(position.slug)?.takeIf(exists) }

/** A folder's items as rows, with whatever comic details are already cached. */
fun comicEntries(
    items: List<ComicFolderItem>,
    metas: Map<String, ComicMeta>,
    positions: Map<String, ComicPosition>,
    statuses: Map<String, ReadingStatus>,
    folderSummary: (String) -> String,
): List<ComicEntry> = items.map { item ->
    when (item.kind) {
        ComicItemKind.FOLDER -> ComicEntry.Folder(item.path, item.title, folderSummary(item.path))
        ComicItemKind.COMIC -> comicEntry(item.path, metas[item.path], positions, statuses)
        ComicItemKind.NOTE -> ComicEntry.Note(item.path, item.title)
    }
}

/** The details rows for a comic, top to bottom (the title and folder are drawn above them). */
fun comicDetailRows(
    meta: ComicMeta,
    position: ComicPosition?,
    status: ReadingStatus,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<DetailRow> = listOf(
    DetailRow("Pages", NumberFormat.getIntegerInstance(locale).format(meta.pageCount)),
    DetailRow("Progress", comicProgressText(meta, position, status)),
    DetailRow("Folder", parentPath(meta.path).ifEmpty { "Comics" }),
    DetailRow("File", meta.path.substringAfterLast('/')),
    DetailRow("File size", fileSizeText(meta.source.size)),
    DetailRow("Added", dateText(meta.source.modified, locale, zone)),
    DetailRow("Last read", position?.let { dateText(it.updatedAt, locale, zone) } ?: "Not yet"),
)
