package com.thelightphone.listen.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import kotlin.math.max
import kotlin.math.roundToInt

/** How big a picture is kept: its shorter side in pixels. */
enum class ArtSize(val px: Int) {
    /** List rows (drawn about 150 px on the 1080 px wide screen). */
    THUMB(160),

    /** Now Playing and the album screen. */
    LARGE(800),
}

/**
 * Where an album's (or a book's) picture comes from. [key] names the picture and changes
 * whenever it could have changed (see `artSourceFor`). [audioFile]'s embedded picture is
 * tried first, then the picture files named [folderImages] in [folder].
 */
data class ArtSource(
    val key: String,
    val audioFile: File?,
    val folder: File?,
    val folderImages: List<String> = MUSIC_FOLDER_IMAGES,
)

/** Picture files next to an album's songs, in the order they're tried. */
val MUSIC_FOLDER_IMAGES = listOf("cover.jpg", "folder.jpg", "cover.png", "folder.png", "cover.jpeg", "folder.jpeg")

/** A picture once looked for: found, or there is none. */
sealed interface Art {
    data class Found(val image: ImageBitmap) : Art
    data object Missing : Art
}

/**
 * Album and book pictures, decoded small (embedded art can be 2,800 px square) and kept
 * three ways:
 *  - in memory, the most recent ones up to [MEMORY_BYTES], so scrolling back is instant;
 *  - on disk in the app's own files (filesDir/cache/art), so each picture is read out of its
 *    song file only once; it can always be rebuilt;
 *  - while loading, one shared job per picture, so ten rows of the same album decode it once.
 * All reading and decoding happens off the main thread, at most [PARALLEL] at a time.
 */
object ArtworkCache {
    private const val MEMORY_BYTES = 24L * 1024 * 1024
    private const val PARALLEL = 2
    private const val TAG = "Listen"

    private val work = Dispatchers.IO.limitedParallelism(PARALLEL)
    private val scope = CoroutineScope(SupervisorJob() + work)

    @Volatile
    private var diskDir: File? = null

    private val memory = LinkedHashMap<String, Art>(64, 0.75f, true)
    private var memoryBytes = 0L
    private val loading = HashMap<String, Deferred<Art>>()

    /** Where pictures are kept on disk: [filesDir]/cache/art. Called by every screen. */
    fun setFilesDir(filesDir: File) {
        if (diskDir == null) diskDir = File(filesDir, "cache/art")
    }

    private fun memoryKey(source: ArtSource, size: ArtSize) = "${source.key}@${size.name}"

    /** The picture if it's in memory now; null means it has to be loaded ([load]). */
    @Synchronized
    fun cached(source: ArtSource, size: ArtSize): Art? = memory[memoryKey(source, size)]

    /** The picture of [source] at [size], from memory, disk, or the files themselves. */
    suspend fun load(source: ArtSource, size: ArtSize): Art {
        val key = memoryKey(source, size)
        val job = synchronized(this) {
            memory[key]?.let { return it }
            loading.getOrPut(key) {
                scope.async {
                    val art = try {
                        readOrMake(source, size)
                    } catch (e: Exception) {
                        Log.w(TAG, "Can't read art for ${source.audioFile ?: source.folder}: $e")
                        Art.Missing
                    }
                    synchronized(this@ArtworkCache) {
                        keep(key, art)
                        loading.remove(key)
                    }
                    art
                }
            }
        }
        return job.await()
    }

    // ---- Disk ----

    /** The cached file if there is one; otherwise reads the picture and caches it. */
    private fun readOrMake(source: ArtSource, size: ArtSize): Art {
        val dir = diskDir
        // A picture file added next to the songs changes the folder's time, so it's noticed.
        val folderTime = source.folder?.lastModified() ?: 0L
        val name = "${hash("${source.key}|$folderTime")}-${size.px}"
        val picture = dir?.let { File(it, "$name.jpg") }
        val none = dir?.let { File(it, "$name.none") }
        if (none != null && none.exists()) return Art.Missing
        if (picture != null && picture.isFile) {
            BitmapFactory.decodeFile(picture.path)?.let { return found(it) }
        }

        val bitmap = decodeFromSource(source, size.px)
        if (dir != null && (dir.isDirectory || dir.mkdirs())) {
            try {
                if (bitmap == null) none?.createNewFile() else picture?.let { writeJpeg(bitmap, it) }
            } catch (e: Exception) {
                Log.w(TAG, "Can't cache art: $e")
            }
        }
        return bitmap?.let(::found) ?: Art.Missing
    }

    private fun found(bitmap: Bitmap): Art {
        bitmap.prepareToDraw()
        return Art.Found(bitmap.asImageBitmap())
    }

    private fun writeJpeg(bitmap: Bitmap, file: File) {
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (!tmp.renameTo(file)) tmp.delete()
    }

    private fun hash(text: String): String {
        val crc = CRC32().apply { update(text.toByteArray()) }
        return "%08x%08x".format(crc.value, text.hashCode())
    }

    // ---- Decoding ----

    /** The embedded picture (any picture type), then a picture file in the folder; scaled to [px]. */
    private fun decodeFromSource(source: ArtSource, px: Int): Bitmap? {
        source.audioFile?.let(::embeddedPicture)?.let { bytes ->
            decodeScaled(px) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }?.let { return it }
        }
        val folder = source.folder ?: return null
        val image = source.folderImages.map { File(folder, it) }.firstOrNull { it.isFile } ?: return null
        return decodeScaled(px) { BitmapFactory.decodeFile(image.path, it) }
    }

    private fun embeddedPicture(file: File): ByteArray? {
        if (!file.isFile) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.embeddedPicture
        } catch (e: RuntimeException) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Reads the picture's size first, decodes it at the largest power-of-two step down that
     * keeps its shorter side at least [px], then scales it so the shorter side is exactly [px]
     * (never up). A 2,800 px cover is never held at full size.
     */
    private fun decodeScaled(px: Int, decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, px)
        val decoded = decode(BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val ratio = px.toFloat() / minOf(decoded.width, decoded.height)
        if (ratio >= 1f) return decoded
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            max(1, (decoded.width * ratio).roundToInt()),
            max(1, (decoded.height * ratio).roundToInt()),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    // ---- Memory ----

    private fun keep(key: String, art: Art) {
        memory.put(key, art)?.let { memoryBytes -= bytesOf(it) }
        memoryBytes += bytesOf(art)
        val oldest = memory.entries.iterator()
        while (memoryBytes > MEMORY_BYTES && oldest.hasNext()) {
            val entry = oldest.next()
            if (entry.key == key) continue
            memoryBytes -= bytesOf(entry.value)
            oldest.remove()
        }
    }

    private fun bytesOf(art: Art): Long =
        if (art is Art.Found) art.image.width.toLong() * art.image.height * 4 else 0L

    private const val JPEG_QUALITY = 90
}

/**
 * The largest power of 2 that shrinks a [width] × [height] picture while keeping its shorter
 * side at least [minSide] (1 when it's already small).
 */
fun sampleSizeFor(width: Int, height: Int, minSide: Int): Int {
    var sample = 1
    while (minOf(width, height) / (sample * 2) >= minSide) sample *= 2
    return sample
}
