package com.thelightphone.listen.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.listen.ListenScreen
import com.thelightphone.listen.artwork.ArtImage
import com.thelightphone.listen.artwork.ArtSize
import com.thelightphone.listen.artwork.artLetter
import com.thelightphone.listen.artwork.artSource
import com.thelightphone.listen.music.ListTopBar
import com.thelightphone.listen.music.MusicLazyList
import com.thelightphone.listen.playback.PlaybackHub
import com.thelightphone.listen.playback.formatTime
import com.thelightphone.listen.ui.ActionButton
import com.thelightphone.listen.ui.CenteredMessage
import com.thelightphone.listen.ui.HairlineDivider
import com.thelightphone.listen.ui.LocalListenAccent
import com.thelightphone.listen.ui.NowPlayingBar
import com.thelightphone.listen.ui.OneLine
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.UniformRow
import com.thelightphone.listen.ui.UpdatingLine
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** The cover at the top of the book screen (grid units square). */
private const val COVER_GRID_UNITS = 9f

/** Chapter rows (grid units, divider included). */
const val CHAPTER_ROW_GRID_UNITS = 4f

/**
 * One audiobook: cover, title, author, narrator, series and number, total length and time
 * left, Play, Mark finished and Start over, then the chapter list with the current chapter
 * marked. Only the chapter rows on screen are drawn, so a 560-file book opens at once.
 */
class BookScreen(sealedActivity: SealedLightActivity, private val bookId: String) : ListenScreen(sealedActivity) {

    @Composable
    override fun Content() {
        val library by BookLibrary.state.collectAsState()
        val positions by BookPositions.positions.collectAsState()
        val book = library.book(bookId)
        ThemedScreen {
            ListTopBar(title = "Audiobook", onBack = { goBack() })
            val area = Modifier.weight(1f)
            when {
                !library.loaded -> Box(modifier = area)
                book == null -> CenteredMessage("This book isn't on the phone any more.", modifier = area)
                else -> Box(modifier = area) { BookDetails(book, positions[book.id]) }
            }
            UpdatingLine(visible = library.updating)
            NowPlayingBar(onOpen = ::openNowPlaying)
        }
    }

    @Composable
    private fun BookDetails(book: Book, saved: BookPosition?) {
        val chapters = remember(book) { chaptersOf(book) }
        val loaded by PlaybackHub.book.collectAsState()
        val playing by PlaybackHub.isPlaying.collectAsState()
        val index by PlaybackHub.index.collectAsState()
        val positionMs by PlaybackHub.positionMs.collectAsState()
        val isLoaded = loaded?.id == book.id

        // The chapter to mark: where the player is when this book is loaded, else the saved place.
        val here = when {
            isLoaded -> BookPosition(fileIndex = index.coerceAtLeast(0), positionMs = positionMs)
            isInProgress(book, saved) -> saved
            else -> null
        }
        val current = here?.let { chapterIndexAt(chapters, it) }

        MusicLazyList(tag = book.id, rowGridUnits = CHAPTER_ROW_GRID_UNITS) {
            item(key = "header") { Header(book) }
            item(key = "progress") { Progress(book, saved, chapters) }
            item(key = "buttons") {
                Buttons(
                    book = book,
                    saved = saved,
                    playLabel = when {
                        isLoaded && playing -> "Pause"
                        isInProgress(book, saved) -> "Resume"
                        else -> "Play"
                    },
                    onPlay = {
                        if (isLoaded && playing) {
                            PlaybackHub.pause()
                        } else {
                            PlaybackHub.playBook(book)
                            openNowPlaying()
                        }
                    },
                )
            }
            item(key = "chapters-label") {
                Column {
                    LightText(
                        text = if (chapters.size == 1) "1 chapter" else "${chapters.size} chapters",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 1f.gridUnitsAsDp(), bottom = 0.25f.gridUnitsAsDp()),
                    )
                    HairlineDivider()
                }
            }
            itemsIndexed(chapters, key = { i, _ -> "chapter:$i" }) { i, chapter ->
                ChapterRow(
                    number = i + 1,
                    chapter = chapter,
                    isCurrent = i == current,
                    showDivider = i != chapters.lastIndex,
                    onClick = {
                        PlaybackHub.playBook(book, chapter)
                        openNowPlaying()
                    },
                )
            }
        }
    }

    @Composable
    private fun Buttons(book: Book, saved: BookPosition?, playLabel: String, onPlay: () -> Unit) {
        val finished = isFinished(book, saved)
        val started = finished || isInProgress(book, saved)
        Column(
            modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
            verticalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
        ) {
            ActionButton(
                label = playLabel,
                icon = if (playLabel == "Pause") LightIcons.PAUSE else LightIcons.PLAY,
                modifier = Modifier.fillMaxWidth(),
                onClick = onPlay,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp())) {
                ActionButton("Mark finished", LightIcons.ACCEPT, Modifier.weight(1f), enabled = !finished) {
                    BookPositions.markFinished(book.id)
                }
                ActionButton("Start over", LightIcons.REFRESH, Modifier.weight(1f), enabled = started) {
                    PlaybackHub.startBookOver(book)
                }
            }
        }
    }
}

/** The cover at the left; title (up to two lines), author, narrator, series and year beside it. */
@Composable
private fun Header(book: Book) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ArtImage(
            source = book.artSource(),
            size = ArtSize.LARGE,
            letter = artLetter(book.title),
            modifier = Modifier.size(COVER_GRID_UNITS.gridUnitsAsDp()),
        )
        Spacer(modifier = Modifier.width(1f.gridUnitsAsDp()))
        Column(modifier = Modifier.weight(1f)) {
            LightText(
                text = book.title,
                variant = LightTextVariant.Subheading,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(0.25f.gridUnitsAsDp()))
            OneLine(text = book.author, variant = LightTextVariant.Copy, lighten = true)
            if (book.narrator.isNotBlank()) {
                OneLine(text = "Read by ${book.narrator}", variant = LightTextVariant.Detail, lighten = true)
            }
            seriesLine(book)?.let { OneLine(text = it, variant = LightTextVariant.Detail, lighten = true) }
            if (book.year.isNotBlank()) OneLine(text = book.year, variant = LightTextVariant.Detail, lighten = true)
        }
    }
}

/**
 * "12 hr 5 min", then when started: "43% · 6 hr 50 min left" and "Chapter 12 of 40, 14:32
 * left in chapter" (or "Finished").
 */
@Composable
private fun Progress(book: Book, saved: BookPosition?, chapters: List<BookChapter>) {
    Column(modifier = Modifier.padding(top = 1f.gridUnitsAsDp())) {
        OneLine(
            text = if (book.durationMs > 0) "Length ${bookLengthText(book.durationMs)}" else "Length not known yet",
            variant = LightTextVariant.Detail,
            lighten = true,
        )
        when {
            isFinished(book, saved) -> AccentLine("Finished")
            isInProgress(book, saved) -> {
                ProgressLine(book, saved)
                chapterSpotText(chapters, saved!!)?.let {
                    OneLine(text = it, variant = LightTextVariant.Detail, lighten = true)
                }
            }
        }
    }
}

/** Number (or a play mark on the current chapter), title, and length. Tapping it plays from there. */
@Composable
fun ChapterRow(number: Int, chapter: BookChapter, isCurrent: Boolean, showDivider: Boolean, onClick: () -> Unit) {
    UniformRow(
        heightGridUnits = CHAPTER_ROW_GRID_UNITS,
        showDivider = showDivider,
        modifier = Modifier.lightClickable(onClickLabel = "Play from here", onClick = onClick),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(2.5f.gridUnitsAsDp())) {
                if (isCurrent) {
                    LightIcon(icon = LightIcons.PLAY, size = 1.2f, contentDescription = "Current chapter")
                } else {
                    LightText(text = number.toString(), variant = LightTextVariant.Detail, lighten = true, maxLines = 1)
                }
            }
            LightText(
                text = chapter.title,
                variant = LightTextVariant.Copy,
                color = if (isCurrent) LocalListenAccent.current else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            chapter.durationMs?.let {
                LightText(
                    text = formatTime(it),
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
                )
            }
        }
    }
}
