package com.thelightphone.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.thelightphone.reader.data.ComicMeta
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.io.File

/**
 * A comics folder's rows (or a list's comics), the same size as the Library's book rows:
 * subfolders (an arrow block, name, "3 folders · 12 comics"), comics (cover, title, page count,
 * progress) and notes (a pencil block, title, "Note"). Only the rows on screen are drawn.
 * [continueReading], when given, is a highlighted first row that opens that comic.
 */
@Composable
fun ComicEntryList(
    state: ComicFolderState,
    coverFile: (ComicMeta) -> File,
    onOpen: (ComicEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = state.entries
    val continueReading = state.continueReading
    LightLazyScrollView(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        uniformItemHeightGridUnits = ENTRY_ROW_GRID_UNITS,
    ) {
        if (continueReading != null) {
            item(key = "continue:" + continueReading.path) {
                UniformRow(
                    heightGridUnits = ENTRY_ROW_GRID_UNITS,
                    showDivider = entries.isNotEmpty(),
                    modifier = Modifier
                        .background(LocalReaderAccent.current.copy(alpha = CONTINUE_TINT_ALPHA))
                        .lightClickable { onOpen(continueReading) },
                ) {
                    CoverAndText(
                        cover = { size -> ComicCover(continueReading, coverFile, size) },
                        modifier = Modifier.padding(horizontal = 0.5f.gridUnitsAsDp()),
                    ) {
                        ContinueRowText(title = continueReading.title, progress = continueReading.progressText)
                    }
                }
            }
        }
        itemsIndexed(entries, key = { _, entry -> entry.path }) { index, entry ->
            // A comic that can't be read (or isn't read yet) isn't tappable; the row says why.
            val canTap = entry !is ComicEntry.Comic || entry.canOpen
            UniformRow(
                heightGridUnits = ENTRY_ROW_GRID_UNITS,
                showDivider = index != entries.lastIndex,
                modifier = if (canTap) Modifier.lightClickable { onOpen(entry) } else Modifier,
            ) {
                CoverAndText(cover = { size -> EntryCover(entry, coverFile, size) }) {
                    EntryText(entry)
                }
            }
        }
    }
}

@Composable
private fun EntryCover(entry: ComicEntry, coverFile: (ComicMeta) -> File, modifier: Modifier) {
    when (entry) {
        is ComicEntry.Comic -> ComicCover(entry, coverFile, modifier)
        is ComicEntry.Folder -> IconPlaceholder(icon = LightIcons.ARROW_RIGHT, modifier = modifier)
        is ComicEntry.Note -> IconPlaceholder(icon = LightIcons.PENCIL, modifier = modifier)
    }
}

/** The comic's first page, small; a blank block until the comic has been read the first time. */
@Composable
private fun ComicCover(comic: ComicEntry.Comic, coverFile: (ComicMeta) -> File, modifier: Modifier) {
    val meta = comic.meta
    if (meta == null) {
        CoverPlaceholder(letter = "", modifier = modifier)
        return
    }
    CachedCover(
        key = "comic/${meta.path}/${meta.source.size}/${meta.source.modified}/${meta.source.version}",
        file = coverFile(meta),
        letter = coverLetter(comic.title),
        modifier = modifier,
    )
}

@Composable
private fun EntryText(entry: ComicEntry) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OneLine(text = entry.title, variant = LightTextVariant.Copy)
        when (entry) {
            is ComicEntry.Folder -> OneLine(text = entry.summary, variant = LightTextVariant.Detail, lighten = true)
            is ComicEntry.Note -> OneLine(text = "Note", variant = LightTextVariant.Detail, lighten = true)
            is ComicEntry.Comic -> {
                OneLine(text = entry.pagesText, variant = LightTextVariant.Detail, lighten = true)
                // "Page 12 of 30" and "Finished" in the accent color; "Not started" stays lighter.
                LightText(
                    text = entry.progressText,
                    variant = LightTextVariant.Detail,
                    lighten = !entry.isStarted,
                    color = if (entry.isStarted) LocalReaderAccent.current else null,
                    maxLines = 1,
                )
            }
        }
    }
}
