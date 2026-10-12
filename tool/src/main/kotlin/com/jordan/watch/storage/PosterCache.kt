package com.jordan.watch.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.jordan.watch.logic.PosterMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File
import java.io.FileOutputStream

/** How wide a poster is kept, in pixels. */
enum class PosterSize(val px: Int) {
    /** The library grid (3 columns on the 1080 px screen = about 340 px; 240 is plenty). */
    GRID(240),

    /** The detail page and backdrops. */
    DETAIL(800),
}

/** A picture once looked for: found, or there is none (draw the first-letter placeholder). */
sealed interface Poster {
    data class Found(val image: ImageBitmap) : Poster
    data object Missing : Poster
}

/**
 * Posters (and backdrops / episode stills), decoded small and kept three ways:
 *  - in memory, the most recent ones up to [MEMORY_BYTES], so scrolling back is instant;
 *  - on disk in the app's own files (filesDir/cache/posters), so a 2,000 px poster.jpg is
 *    decoded at full cost only once; it can always be rebuilt;
 *  - while loading, one shared job per picture, so it's never decoded twice at once.
 * All reading and decoding happens off the main thread, at most [PARALLEL] at a time.
 * (Same as Listen's ArtworkCache.)
 */
object PosterCache {
    private const val MEMORY_BYTES = 16L * 1024 * 1024
    private const val PARALLEL = 2
    private const val JPEG_QUALITY = 88
    private const val TAG = "Watch"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(PARALLEL))

    @Volatile
    private var diskDir: File? = null

    private val memory = LinkedHashMap<String, Poster>(64, 0.75f, true)
    private var memoryBytes = 0L
    private val loading = HashMap<String, Deferred<Poster>>()

    /** Where posters are kept on disk: [filesDir]/cache/posters. Called by every screen. */
    fun setFilesDir(filesDir: File) {
        if (diskDir == null) diskDir = File(filesDir, "cache/posters")
    }

    /**
     * The key for a picture file: its path, size and change time, so a new poster sent from
     * the PC is noticed. Worked out off the main thread (it reads the file's details).
     */
    fun keyFor(file: File, size: PosterSize): String =
        PosterMath.cacheName(file.path, file.length(), file.lastModified(), size.px)

    /** The poster if it's in memory now; null means it has to be [load]ed. */
    @Synchronized
    fun cached(file: File, size: PosterSize): Poster? = memory[file.path + "@" + size.name]

    /** The picture [file] at [size], from memory, disk, or the file itself. */
    suspend fun load(file: File, size: PosterSize): Poster {
        val memoryKey = file.path + "@" + size.name
        val job = synchronized(this) {
            memory[memoryKey]?.let { return it }
            loading.getOrPut(memoryKey) {
                scope.async {
                    val poster = try {
                        readOrMake(file, size)
                    } catch (e: Exception) {
                        Log.w(TAG, "Can't read poster $file: $e")
                        Poster.Missing
                    }
                    synchronized(this@PosterCache) {
                        keep(memoryKey, poster)
                        loading.remove(memoryKey)
                    }
                    poster
                }
            }
        }
        return job.await()
    }

    /** Forgets everything in memory (after a rescan replaced posters). Disk keys change by themselves. */
    @Synchronized
    fun clearMemory() {
        memory.clear()
        memoryBytes = 0
    }

    // ---- Disk ----

    private fun readOrMake(file: File, size: PosterSize): Poster {
        if (!file.isFile) return Poster.Missing
        val cached = diskDir?.let { File(it, keyFor(file, size) + ".jpg") }
        if (cached != null && cached.isFile) {
            BitmapFactory.decodeFile(cached.path)?.let { return found(it) }
        }
        val bitmap = decodeScaled(file, size.px) ?: return Poster.Missing
        if (cached != null) {
            try {
                writeJpeg(bitmap, cached)
            } catch (e: Exception) {
                Log.w(TAG, "Can't cache poster: $e")
            }
        }
        return found(bitmap)
    }

    private fun writeJpeg(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    private fun found(bitmap: Bitmap): Poster {
        bitmap.prepareToDraw()
        return Poster.Found(bitmap.asImageBitmap())
    }

    // ---- Decoding ----

    /**
     * Reads the picture's size first, decodes it at the largest power-of-two step down that
     * keeps it at least [px] wide, then scales it to exactly [px] wide (never up). A big
     * poster is never held at full size.
     */
    private fun decodeScaled(file: File, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = PosterMath.sampleSize(bounds.outWidth, px)
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val (width, height) = PosterMath.scaledSize(decoded.width, decoded.height, px)
        if (width == decoded.width) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, width, height, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    // ---- Memory ----

    private fun keep(key: String, poster: Poster) {
        memory.put(key, poster)?.let { memoryBytes -= bytesOf(it) }
        memoryBytes += bytesOf(poster)
        val oldest = memory.entries.iterator()
        while (memoryBytes > MEMORY_BYTES && oldest.hasNext()) {
            val entry = oldest.next()
            if (entry.key == key) continue
            memoryBytes -= bytesOf(entry.value)
            oldest.remove()
        }
    }

    private fun bytesOf(poster: Poster): Long =
        if (poster is Poster.Found) poster.image.width.toLong() * poster.image.height * 4 else 0L
}
