package com.thelightphone.listen.podcasts.download

import com.thelightphone.listen.podcasts.net.Http
import com.thelightphone.listen.podcasts.net.HttpTransport
import com.thelightphone.listen.podcasts.net.NetError
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Not enough room on the phone for an episode. */
class NotEnoughSpace(val neededBytes: Long) :
    IOException("Not enough space on the phone (it needs ${sizeText(neededBytes)} and keeps 1 GB free).")

/** The download address answered with a web page or text instead of audio. */
class NotAudio : IOException("The download link gives a web page, not audio. Try again later.")

/**
 * Downloads one file straight to disk in 64 KB chunks (never held in memory) into
 * `<name>.part`, resuming from where an earlier attempt stopped (HTTP Range), and renames it
 * to [target] once complete. Blocking: call off the main thread.
 */
object ResumableDownload {
    private const val CHUNK = 64 * 1024

    /** Keep this much free on the phone, as the PC script does. */
    const val KEEP_FREE_BYTES = 1L * 1024 * 1024 * 1024

    /**
     * Downloads [url] to [target]. [onProgress] gets (bytes so far, total or null) at most
     * every [progressEveryMs]. [checkCancelled] is called between chunks and should throw to
     * stop (the partial file is kept, so the next attempt resumes). [freeBytes] says how much
     * room is left. Returns the file's size.
     */
    fun download(
        transport: HttpTransport,
        url: String,
        target: File,
        freeBytes: () -> Long,
        onProgress: (Long, Long?) -> Unit,
        checkCancelled: () -> Unit = {},
        progressEveryMs: Long = 500,
        clock: () -> Long = System::currentTimeMillis,
    ): Long {
        val part = partFileFor(target)
        target.parentFile?.mkdirs()
        var have = if (part.isFile) part.length() else 0L
        val headers = if (have > 0) mapOf("Range" to "bytes=$have-") else emptyMap()

        val opened = try {
            Http.open(transport, url, headers)
        } catch (e: NetError.HttpStatus) {
            // 416: the part file already holds the whole thing.
            if (e.code == 416 && have > 0) return finish(part, target)
            throw e
        }
        opened.use {
            val response = it.response
            if (response.contentType?.lowercase()?.startsWith("text/html") == true) throw NotAudio()
            val resuming = have > 0 && response.code == 206
            if (!resuming) have = 0 // the server sent it all again: start over
            val remaining = response.contentLength.takeIf { n -> n > 0 }
            val total = remaining?.let { n -> n + have }
            if (remaining != null && remaining > freeBytes() - KEEP_FREE_BYTES) throw NotEnoughSpace(remaining)

            FileOutputStream(part, resuming).use { out ->
                val buffer = ByteArray(CHUNK)
                var lastReport = 0L
                onProgress(have, total)
                while (true) {
                    checkCancelled()
                    val n = response.body.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    have += n
                    val now = clock()
                    if (now - lastReport >= progressEveryMs) {
                        lastReport = now
                        onProgress(have, total)
                    }
                }
                out.fd.sync()
            }
            if (total != null && have < total) throw IOException("The download stopped early ($have of $total bytes).")
            onProgress(have, total)
        }
        return finish(part, target)
    }

    fun partFileFor(target: File) = File(target.parentFile, target.name + ".part")

    private fun finish(part: File, target: File): Long {
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("Couldn't save ${target.name}")
        return target.length()
    }
}
