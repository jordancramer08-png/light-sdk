package com.thelightphone.listen.podcasts.download

import com.thelightphone.listen.podcasts.feed.TranscriptLink
import com.thelightphone.listen.podcasts.transcripts.TranscriptFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadNamesTest {

    @Test
    fun `audio file endings`() {
        assertEquals("m4a", audioExtension("audio/mpeg", "https://a.com/ep.M4A?token=1"))
        assertEquals("mp3", audioExtension("audio/mpeg", "https://dts.podtrac.com/redirect.mp3/a.com/ep"))
        assertEquals("m4a", audioExtension("audio/x-m4a", "https://a.com/stream?id=5"))
        assertEquals("ogg", audioExtension("audio/ogg; codecs=vorbis", "https://a.com/ep"))
        assertEquals("mp3", audioExtension(null, "https://a.com/ep.php"))
    }

    @Test
    fun `the best transcript is picked`() {
        val links = listOf(
            TranscriptLink("https://a/t.html", "text/html"),
            TranscriptLink("https://a/t.json", "application/json"),
            TranscriptLink("https://a/t.pdf", "application/pdf"),
            TranscriptLink("https://a/t.srt", "application/x-subrip"),
        )
        assertEquals("https://a/t.srt" to TranscriptFormat.SRT, pickTranscript(links)!!.let { it.first.url to it.second })
        assertNull(pickTranscript(listOf(TranscriptLink("https://a/t.pdf", "application/pdf"))))
        assertEquals("vtt", transcriptExtension(TranscriptFormat.VTT))
    }

    @Test
    fun `sizes read naturally`() {
        assertEquals("820 KB", sizeText(820 * 1024))
        assertEquals("54 MB", sizeText(54L * 1024 * 1024))
        assertEquals("1.2 GB", sizeText((1.2 * 1024 * 1024 * 1024).toLong()))
        assertEquals("0 KB", sizeText(0))
    }

    @Test
    fun `download progress in words`() {
        val mb = 1024L * 1024
        assertEquals("Downloading… 42% · 23 MB of 54 MB", downloadProgressText(DownloadStatus.Active(23 * mb, 54 * mb, waiting = false)))
        assertEquals("Waiting to download…", downloadProgressText(DownloadStatus.Active(0, null, waiting = true)))
        assertEquals("Paused at 50%. It carries on by itself.", downloadProgressText(DownloadStatus.Active(5, 10, waiting = true)))
        assertEquals("Downloading 50%", downloadRowText(DownloadStatus.Active(5, 10, waiting = false)))
        assertEquals("Download failed", downloadRowText(DownloadStatus.Failed("x")))
        assertEquals("", downloadRowText(null))
    }
}
