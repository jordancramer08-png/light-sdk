package com.thelightphone.listen.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A song's album art, decoded small enough for the screen: the embedded picture first,
 * then cover.jpg / folder.jpg (or .png) beside the file. Embedded art can be 2,800 px
 * square, so it is never decoded at full size. Decoding happens off the main thread and
 * the last few results stay in memory. (Session 3 adds a disk cache and list thumbnails.)
 */
object SongArtwork {
    private val memory = LruCache<String, ImageBitmap>(6)

    /** Art for [file] about [targetPx] square, or null (no art, or it couldn't be read). */
    suspend fun load(file: File, targetPx: Int): ImageBitmap? {
        val key = "${file.path}@$targetPx"
        memory.get(key)?.let { return it }
        val image = withContext(Dispatchers.IO) {
            runCatching { decode(file, targetPx)?.asImageBitmap() }.getOrNull()
        }
        if (image != null) memory.put(key, image)
        return image
    }

    private fun decode(file: File, targetPx: Int): Bitmap? {
        embeddedPicture(file)?.let { bytes ->
            decodeSampled(targetPx, { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) })?.let { return it }
        }
        val folderImage = FOLDER_IMAGES.map { File(file.parentFile, it) }.firstOrNull { it.isFile }
            ?: return null
        return decodeSampled(targetPx, { BitmapFactory.decodeFile(folderImage.path, it) })
    }

    private fun embeddedPicture(file: File): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.embeddedPicture
        } catch (e: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    /** Reads the size first, then decodes at the largest power-of-two step down that's still ≥ [targetPx]. */
    private fun decodeSampled(targetPx: Int, decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
        return decode(BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private val FOLDER_IMAGES = listOf("cover.jpg", "folder.jpg", "cover.png", "folder.png")
}

/** [file]'s art for drawing: null while it loads, and when there is none. */
@Composable
fun rememberSongArtwork(file: File?, targetPx: Int): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, file, targetPx) {
        value = file?.let { SongArtwork.load(it, targetPx) }
    }
