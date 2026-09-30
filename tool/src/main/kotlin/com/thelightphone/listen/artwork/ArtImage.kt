package com.thelightphone.listen.artwork

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import com.thelightphone.listen.books.Book
import com.thelightphone.listen.music.Album
import com.thelightphone.listen.music.Song
import com.thelightphone.listen.storage.ListenPaths
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import java.io.File

/**
 * Where an album's art comes from: its first track's embedded picture, then cover.jpg /
 * folder.jpg in the album folder. The key changes when that first track's file changes.
 */
fun Album.artSource(): ArtSource = songArtSource(songs.first(), albumKey = key)

/**
 * A song's art: its album's, when the song's album is known ([album]); otherwise the
 * song file's own picture (for a queue restored before the library loaded).
 */
fun songArtSource(song: Song, album: Album?): ArtSource =
    album?.artSource() ?: songArtSource(song, albumKey = "song:" + song.path)

private fun songArtSource(first: Song, albumKey: String): ArtSource {
    val file = File(ListenPaths.music, first.path)
    return ArtSource(
        key = "$albumKey|${first.path}|${first.size}|${first.modified}",
        audioFile = file,
        folder = file.parentFile,
    )
}

/**
 * A book's picture: the cover named in book.json first, then the first file's embedded
 * picture; without a book.json cover, the embedded picture and then cover.jpg / folder.jpg.
 */
fun Book.artSource(): ArtSource {
    val dir = File(ListenPaths.audiobooks, folder)
    val first = files.firstOrNull()
    val cover = coverFile
    return ArtSource(
        key = "book:$id|$cover|$coverModified|${first?.path}|${first?.size}|${first?.modified}",
        audioFile = first?.let { File(dir, it.path) },
        folder = dir,
        folderImages = if (cover != null) listOf(cover) else MUSIC_FOLDER_IMAGES,
        folderFirst = cover != null,
    )
}

/** The letter on a placeholder: the name's first letter or digit, or "♪". */
fun artLetter(name: String): String = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "♪"

/**
 * [source]'s picture at [size], filling [modifier]'s box (cropped to it). Drawn at once when
 * it's in memory, otherwise loaded off the main thread; until then, and when there is no
 * picture, a plain block with [letter] is drawn instead.
 */
@Composable
fun ArtImage(
    source: ArtSource?,
    size: ArtSize,
    letter: String,
    modifier: Modifier = Modifier,
    placeholder: @Composable (letter: String, modifier: Modifier) -> Unit = { l, m -> ArtPlaceholder(l, m) },
) {
    var art by remember(source, size) { mutableStateOf(source?.let { ArtworkCache.cached(it, size) }) }
    if (art == null && source != null) {
        LaunchedEffect(source, size) { art = ArtworkCache.load(source, size) }
    }
    when (val shown = art) {
        is Art.Found -> Image(
            bitmap = shown.image,
            contentDescription = null, // the name is written beside it
            contentScale = ContentScale.Crop,
            modifier = modifier.clipToBounds(),
        )
        Art.Missing -> placeholder(letter, modifier)
        // Still loading: a blank block, so a letter doesn't flash before the picture.
        null -> placeholder(if (source == null) letter else "", modifier)
    }
}

/** The plain block drawn where art would be, with [letter] (lighter) in the middle. */
@Composable
fun ArtPlaceholder(letter: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(LightThemeTokens.colors.content.copy(alpha = PLACEHOLDER_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        if (letter.isNotEmpty()) LightText(text = letter, variant = LightTextVariant.Subheading, lighten = true)
    }
}

/** How strongly the placeholder block is tinted with the text color: just enough to see. */
private const val PLACEHOLDER_ALPHA = 0.1f
