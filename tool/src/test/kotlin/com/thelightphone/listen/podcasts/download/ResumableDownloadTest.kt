package com.thelightphone.listen.podcasts.download

import com.thelightphone.listen.podcasts.net.HttpResponse
import com.thelightphone.listen.podcasts.net.HttpTransport
import com.thelightphone.listen.podcasts.net.NetError
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.net.UnknownHostException
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A pretend audio server that honours Range requests (unless told not to) and can break mid-way. */
private class AudioServer(
    val audio: ByteArray,
    var supportsRange: Boolean = true,
    var breakAfter: Int? = null,
    var contentType: String = "audio/mpeg",
) : HttpTransport {
    val requests = mutableListOf<Map<String, String>>()

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        requests += headers
        val from = headers["Range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
        if (from != null && supportsRange && from >= audio.size) {
            return HttpResponse(416, null, contentType, 0, ByteArrayInputStream(ByteArray(0)))
        }
        val start = if (from != null && supportsRange) from else 0
        val bytes = audio.copyOfRange(start, audio.size)
        val limit = breakAfter
        val body: InputStream = object : ByteArrayInputStream(bytes) {
            var sent = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (limit != null && sent >= limit) throw IOException("connection reset")
                val n = super.read(b, off, if (limit != null) minOf(len, limit - sent) else len)
                if (n > 0) sent += n
                return n
            }
        }
        return HttpResponse(if (start > 0) 206 else 200, null, contentType, bytes.size.toLong(), body)
    }
}

class ResumableDownloadTest {

    private val dir: File = Files.createTempDirectory("listen-dl").toFile()
    private val target = File(dir, "show/ep.mp3")
    private val audio = Random(7).nextBytes(1_000_000)
    private val plenty = { 100L * 1024 * 1024 * 1024 }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun download(server: HttpTransport, onProgress: (Long, Long?) -> Unit = { _, _ -> }) =
        ResumableDownload.download(server, "https://a.com/ep.mp3", target, plenty, onProgress)

    @Test
    fun `a whole download lands in the file, with progress`() {
        val progress = mutableListOf<Pair<Long, Long?>>()
        assertEquals(1_000_000L, download(AudioServer(audio)) { have, total -> progress += have to total })
        assertContentEquals(audio, target.readBytes())
        assertFalse(ResumableDownload.partFileFor(target).exists())
        assertEquals(0L to 1_000_000L, progress.first())
        assertEquals(1_000_000L to 1_000_000L, progress.last())
    }

    @Test
    fun `a broken download resumes where it stopped`() {
        val server = AudioServer(audio, breakAfter = 300_000)
        assertFailsWith<IOException> { download(server) }
        assertEquals(300_000L, ResumableDownload.partFileFor(target).length(), "the part is kept")
        assertFalse(target.exists())

        server.breakAfter = null
        download(server)
        assertEquals("bytes=300000-", server.requests.last()["Range"])
        assertContentEquals(audio, target.readBytes())
    }

    @Test
    fun `a server without Range support starts over`() {
        val server = AudioServer(audio, breakAfter = 300_000)
        assertFailsWith<IOException> { download(server) }
        server.breakAfter = null
        server.supportsRange = false
        download(server)
        assertContentEquals(audio, target.readBytes())
    }

    @Test
    fun `a part file that's already complete is just finished`() {
        ResumableDownload.partFileFor(target).also { it.parentFile.mkdirs() }.writeBytes(audio)
        download(AudioServer(audio))
        assertContentEquals(audio, target.readBytes())
    }

    @Test
    fun `cancelling between chunks keeps the part`() {
        var chunks = 0
        assertFailsWith<IllegalStateException> {
            ResumableDownload.download(AudioServer(audio), "https://a.com/ep.mp3", target, plenty, { _, _ -> }, checkCancelled = {
                if (++chunks > 3) throw IllegalStateException("cancelled")
            })
        }
        assertTrue(ResumableDownload.partFileFor(target).length() in 1 until audio.size)
    }

    @Test
    fun `clear failures`() {
        assertFailsWith<NotEnoughSpace> {
            ResumableDownload.download(AudioServer(audio), "https://a.com/ep.mp3", target, { ResumableDownload.KEEP_FREE_BYTES + 10 }, { _, _ -> })
        }
        assertFailsWith<NotAudio> { download(AudioServer(audio, contentType = "text/html; charset=utf-8")) }
        assertFailsWith<NetError.NoConnection> { download(HttpTransport { _, _ -> throw UnknownHostException("a.com") }) }
        assertFalse(target.exists())
    }
}
