package com.thelightphone.listen.podcasts.chapters

import com.thelightphone.listen.podcasts.Samples
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChaptersTest {

    @Test
    fun `podcast chapters JSON`() {
        val list = parsePodcastChaptersJson(Samples.text("chapters.json"))!!
        assertEquals(ChapterSource.PODCAST_JSON, list.source)
        assertEquals(
            listOf(0L to "Welcome", 330_500L to "Main topic", 900_000L to "Chapter 3", 3_600_000L to "Wrap-up & thanks"),
            list.chapters.map { it.startMs to it.title },
            "sorted, silent (toc:false) markers dropped, untitled named, string times read",
        )
        assertEquals("https://example.com/topic", list.chapters[1].url)
        assertEquals(3_720_000L, list.chapters[3].endMs)
    }

    @Test
    fun `broken or empty chapter files give no chapters`() {
        assertNull(parsePodcastChaptersJson("{ not json"))
        assertNull(parsePodcastChaptersJson("""{"version":"1.2.0","chapters":[]}"""))
        assertNull(parsePodcastChaptersJson("[]"))
        assertNull(parsePodcastChaptersJson("\"just a string\""))
    }

    @Test
    fun `psc chapters with odd times`() {
        val list = pscChapters(listOf(PscChapter("90", "Ninety s"), PscChapter("bad", "Skipped"), PscChapter("00:00:00.000", "")))!!
        assertEquals(listOf(0L to "Chapter 1", 90_000L to "Ninety s"), list.chapters.map { it.startMs to it.title })
        assertNull(pscChapters(emptyList()))
    }

    // ---- ID3 CHAP ----

    private val dir: File = Files.createTempDirectory("listen-id3").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val NUL = byteArrayOf(0)

    private fun int(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun synchsafe(v: Int) = byteArrayOf((v shr 21 and 0x7F).toByte(), (v shr 14 and 0x7F).toByte(), (v shr 7 and 0x7F).toByte(), (v and 0x7F).toByte())
    private fun frame(id: String, body: ByteArray, v4: Boolean) =
        id.toByteArray() + (if (v4) synchsafe(body.size) else int(body.size.toLong())) + byteArrayOf(0, 0) + body

    private fun utf8Title(t: String, v4: Boolean) = frame("TIT2", byteArrayOf(3) + t.toByteArray(Charsets.UTF_8) + NUL, v4)
    private fun utf16Title(t: String, v4: Boolean) = frame("TIT2", byteArrayOf(1) + t.toByteArray(Charsets.UTF_16), v4)
    private fun link(url: String, v4: Boolean) = frame("WXXX", byteArrayOf(0) + "desc".toByteArray() + NUL + url.toByteArray(), v4)

    private fun chap(id: String, start: Long, end: Long, v4: Boolean, vararg sub: ByteArray) =
        frame("CHAP", id.toByteArray() + NUL + int(start) + int(end) + int(0xFFFFFFFFL) + int(0xFFFFFFFFL) + sub.fold(ByteArray(0)) { a, b -> a + b }, v4)

    private fun ctoc(children: List<String>, v4: Boolean) =
        frame("CTOC", "toc".toByteArray() + NUL + byteArrayOf(0x03, children.size.toByte()) + children.fold(ByteArray(0)) { a, c -> a + c.toByteArray() + NUL }, v4)

    private fun mp3(version: Int, flags: Int = 0, vararg frames: ByteArray): File {
        val body = frames.fold(ByteArray(0)) { a, b -> a + b } + ByteArray(256) // padding
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray())
        out.write(byteArrayOf(version.toByte(), 0, flags.toByte()))
        out.write(synchsafe(body.size))
        out.write(body)
        out.write(ByteArray(4096) { 0x55 }) // pretend audio
        return File(dir, "ep-$version-${frames.size}.mp3").also { it.writeBytes(out.toByteArray()) }
    }

    @Test
    fun `ID3 v2_3 chapters, after a big cover picture that's skipped`() {
        val v4 = false
        val file = mp3(
            3, 0,
            frame("TIT2", byteArrayOf(0) + "Episode".toByteArray(), v4),
            frame("APIC", ByteArray(3_000_000), v4),
            ctoc(listOf("c2", "c1"), v4),
            chap("c1", 0, 60_000, v4, utf8Title("Opening", v4)),
            chap("c2", 60_000, 0xFFFFFFFFL, v4, utf8Title("Ünïcode ✓", v4), link("https://example.com/c2", v4)),
        )
        val list = Id3Chapters.read(file)!!
        assertEquals(ChapterSource.ID3, list.source)
        assertEquals(listOf(0L to "Opening", 60_000L to "Ünïcode ✓"), list.chapters.map { it.startMs to it.title })
        assertEquals(60_000L, list.chapters[0].endMs)
        assertNull(list.chapters[1].endMs, "0xFFFFFFFF means no end")
        assertEquals("https://example.com/c2", list.chapters[1].url)
    }

    @Test
    fun `ID3 v2_4 chapters with UTF-16 titles and synchsafe sizes`() {
        val v4 = true
        val file = mp3(
            4, 0,
            frame("APIC", ByteArray(300_000), v4),
            chap("a", 0, 1_000, v4, utf16Title("Intro", v4)),
            chap("b", 1_000, 2_000, v4),
        )
        assertEquals(listOf("Intro", "Chapter 2"), Id3Chapters.read(file)!!.chapters.map { it.title })
    }

    @Test
    fun `no tag, no chapters, or a broken file give nothing`() {
        assertNull(Id3Chapters.read(File(dir, "missing.mp3")))
        assertNull(Id3Chapters.read(File(dir, "plain.mp3").also { it.writeBytes(ByteArray(5000) { 0x55 }) }))
        assertNull(Id3Chapters.read(mp3(3, 0, frame("TIT2", byteArrayOf(0) + "No chapters".toByteArray(), false))))
        val cut = File(dir, "cut.mp3").also { it.writeBytes(mp3(3, 0, chap("x", 0, 1, false, utf8Title("X", false))).readBytes().copyOf(25)) }
        assertNull(Id3Chapters.read(cut))
        assertNull(Id3Chapters.read(File(dir, "tiny.mp3").also { it.writeBytes("ID3".toByteArray()) }))
    }

    @Test
    fun `an unsynchronised v2_3 tag is read`() {
        // Chapter start 0xFF00 ms: unsynchronisation writes its 0xFF 0x00 bytes as 0xFF 0x00 0x00.
        val v4 = false
        val plainTag = chap("u", 0xFF00, 0, v4, utf8Title("Unsync", v4))
        val unsynced = ByteArrayOutputStream().apply {
            for (i in plainTag.indices) {
                write(plainTag[i].toInt())
                if ((plainTag[i].toInt() and 0xFF) == 0xFF && i + 1 < plainTag.size && (plainTag[i + 1].toInt() and 0xE0 == 0xE0 || plainTag[i + 1].toInt() == 0)) write(0)
            }
        }.toByteArray()
        val file = mp3(3, 0x80, unsynced)
        assertEquals(listOf(0xFF00L to "Unsync"), Id3Chapters.read(file)!!.chapters.map { it.startMs to it.title })
    }
}
