package com.thelightphone.reader

import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.CoverSize
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A book's cover picture once looked for: found, or the book has none. */
sealed interface CoverPicture {
    data class Found(val image: ImageBitmap) : CoverPicture
    data object Missing : CoverPicture
}

/**
 * Cover pictures read recently, kept in memory so scrolling back over a row doesn't read its
 * file again. The oldest are dropped once they add up to [MAX_BYTES].
 */
object CoverCache {
    private const val MAX_BYTES = 16L * 1024 * 1024

    private val pictures = LinkedHashMap<String, CoverPicture>(64, 0.75f, true)
    private var totalBytes = 0L

    /** Which picture of which book: it changes whenever the book is prepared again. */
    fun key(book: BookMeta, size: CoverSize): String =
        "${book.slug}/${size.name}/${book.source.size}/${book.source.modified}/${book.source.parserVersion}"

    /** The picture if it's in memory now; null means it has to be read ([load]). */
    @Synchronized
    fun cached(key: String): CoverPicture? = pictures[key]

    /** Reads the picture from [file] off the main thread, and keeps it. */
    suspend fun load(file: File, key: String): CoverPicture {
        cached(key)?.let { return it }
        val picture = withContext(Dispatchers.IO) { read(file) }
        keep(key, picture)
        return picture
    }

    private fun read(file: File): CoverPicture {
        if (!file.isFile) return CoverPicture.Missing
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return CoverPicture.Missing
        bitmap.prepareToDraw()
        return CoverPicture.Found(bitmap.asImageBitmap())
    }

    @Synchronized
    private fun keep(key: String, picture: CoverPicture) {
        pictures.put(key, picture)?.let { totalBytes -= bytesOf(it) }
        totalBytes += bytesOf(picture)
        val oldest = pictures.entries.iterator()
        while (totalBytes > MAX_BYTES && oldest.hasNext()) {
            val entry = oldest.next()
            if (entry.key == key) continue
            totalBytes -= bytesOf(entry.value)
            oldest.remove()
        }
    }

    private fun bytesOf(picture: CoverPicture): Long =
        if (picture is CoverPicture.Found) picture.image.width.toLong() * picture.image.height * 4 else 0L
}

/**
 * A book's small cover, filling [modifier]'s box (cropped to it). Drawn from memory when it
 * was shown recently, otherwise read off the main thread. A book without a cover gets a plain
 * block with its title's first letter; while the picture loads, the block is blank.
 */
@Composable
fun BookCover(book: BookMeta, file: File, modifier: Modifier = Modifier) {
    CachedCover(key = CoverCache.key(book, CoverSize.SMALL), file = file, letter = coverLetter(book.title), modifier = modifier)
}

/**
 * A small cover picture read from [file], kept in [CoverCache] under [key]; [letter] is shown
 * on the plain block when there is no picture. Used by book and comic rows alike.
 */
@Composable
fun CachedCover(key: String, file: File, letter: String, modifier: Modifier = Modifier) {
    var picture by remember(key) { mutableStateOf(CoverCache.cached(key)) }
    if (picture == null) {
        LaunchedEffect(key) { picture = CoverCache.load(file, key) }
    }
    when (val shown = picture) {
        is CoverPicture.Found -> Image(
            bitmap = shown.image,
            contentDescription = null, // the title is written beside it
            contentScale = ContentScale.Crop,
            modifier = modifier.clipToBounds(),
        )
        CoverPicture.Missing -> CoverPlaceholder(letter = letter, modifier = modifier)
        null -> CoverPlaceholder(letter = "", modifier = modifier)
    }
}

/** The plain block drawn where a cover would be, with [letter] (lighter) in the middle. */
@Composable
fun CoverPlaceholder(letter: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(LightThemeTokens.colors.content.copy(alpha = PLACEHOLDER_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        if (letter.isNotEmpty()) LightText(text = letter, variant = LightTextVariant.Subheading, lighten = true)
    }
}

/** The plain block with an icon in it, for rows that aren't books or comics (a folder, a note). */
@Composable
fun IconPlaceholder(icon: LightIconConfiguration, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(LightThemeTokens.colors.content.copy(alpha = PLACEHOLDER_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        LightIcon(icon = icon, contentDescription = null)
    }
}

/** How strongly the placeholder block is tinted with the text color: just enough to see. */
private const val PLACEHOLDER_ALPHA = 0.1f
