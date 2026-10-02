package com.thelightphone.listen.podcasts.transcripts

import com.thelightphone.listen.podcasts.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptsTest {

    private fun parse(format: TranscriptFormat, sample: String) = Transcripts.parse(format, Samples.text(sample))!!

    @Test
    fun `WebVTT with speakers, settings, notes and styles`() {
        val t = parse(TranscriptFormat.VTT, "transcript.vtt")
        assertEquals(
            listOf(
                TranscriptLine("Welcome to the show, everyone.", 0, 4_200, "Ann"),
                TranscriptLine("Thanks, Ann. It's great to be here.", 4_200, 9_000, "Bob"),
                TranscriptLine("Short timestamps without hours & no speaker.", 9_500, 15_000, null),
                TranscriptLine("Over an hour in.", 3_723_250, 3_725_000, null),
            ),
            t.lines,
        )
        assertTrue(t.timed)
    }

    @Test
    fun `SRT with comma times and speaker prefixes`() {
        val t = parse(TranscriptFormat.SRT, "transcript.srt")
        assertEquals(
            listOf(
                TranscriptLine("Welcome to the show, everyone.", 0, 4_200, "Ann"),
                TranscriptLine("Thanks, Ann.", 4_200, 9_000, "Bob"),
                TranscriptLine("A line without a speaker.", 9_500, 15_000, null),
                TranscriptLine("Over an hour in.", 3_723_250, 3_725_000, null),
            ),
            t.lines,
        )
    }

    @Test
    fun `Podcasting 2_0 JSON joins word-level segments into lines`() {
        val t = parse(TranscriptFormat.JSON, "transcript.json")
        assertEquals(
            listOf(
                TranscriptLine("Welcome to the show.", 0, 1_400, "Ann"),
                TranscriptLine("Thanks Ann", 2_000, 2_900, "Bob"),
                TranscriptLine("After a pause", 9_000, 10_000, "Bob"),
                TranscriptLine("No speaker given.", 13_500, 14_000, null),
            ),
            t.lines,
        )
    }

    @Test
    fun `HTML with cite and time tags, and an inline timestamp`() {
        val t = parse(TranscriptFormat.HTML, "transcript.html")
        assertEquals(
            listOf(
                TranscriptLine("Welcome to the show, everyone.", 0, null, "Ann"),
                TranscriptLine("Thanks, Ann. It’s great to be here.", 4_000, null, "Bob"),
                TranscriptLine("A timestamp written inline.", 90_000, null, "Ann"),
            ),
            t.lines,
        )
    }

    @Test
    fun `plain text is untimed paragraphs`() {
        val t = parse(TranscriptFormat.TEXT, "transcript.txt")
        assertEquals(3, t.lines.size)
        assertFalse(t.timed)
        assertEquals(-1, t.lineAt(5_000))
        assertEquals("And this is the last paragraph.", t.lines.last().text)
    }

    @Test
    fun `plain text with leading times is timed`() {
        val t = Transcripts.parse(TranscriptFormat.TEXT, "[00:00] Ann: Hi.\n(00:05) Bob: Hello.\n1:02:03 Later on.")!!
        assertEquals(listOf(0L to "Ann", 5_000L to "Bob", 3_723_000L to null), t.lines.map { it.startMs to it.speaker })
        assertTrue(t.timed)
    }

    @Test
    fun `the line playing is found by position`() {
        val t = parse(TranscriptFormat.VTT, "transcript.vtt")
        assertEquals(0, t.lineAt(0))
        assertEquals(0, t.lineAt(4_199))
        assertEquals(1, t.lineAt(4_200))
        assertEquals(2, t.lineAt(60_000))
        assertEquals(3, t.lineAt(99_999_999))
    }

    @Test
    fun `format from type, or from the file name when the type is vague`() {
        assertEquals(TranscriptFormat.VTT, Transcripts.formatOf("text/vtt", null))
        assertEquals(TranscriptFormat.SRT, Transcripts.formatOf("application/x-subrip", null))
        assertEquals(TranscriptFormat.SRT, Transcripts.formatOf("application/srt", null))
        assertEquals(TranscriptFormat.JSON, Transcripts.formatOf("application/json; charset=utf-8", null))
        assertEquals(TranscriptFormat.HTML, Transcripts.formatOf("TEXT/HTML", null))
        assertEquals(TranscriptFormat.TEXT, Transcripts.formatOf("text/plain", null))
        assertEquals(TranscriptFormat.VTT, Transcripts.formatOf("application/octet-stream", "https://a.com/t.vtt?x=1"))
        assertNull(Transcripts.formatOf("application/pdf", "https://a.com/t.pdf"))
    }

    @Test
    fun `junk and oversized transcripts give nothing`() {
        assertNull(Transcripts.parse(TranscriptFormat.JSON, "{ not json"))
        assertNull(Transcripts.parse(TranscriptFormat.VTT, "WEBVTT\n\nNOTE nothing here"))
        assertNull(Transcripts.parse(TranscriptFormat.TEXT, "   \n\n  "))
        assertNull(Transcripts.parse(TranscriptFormat.TEXT, "x".repeat(MAX_TRANSCRIPT_BYTES + 1)))
    }

    @Test
    fun `bracketed speakers, with machine labels dropped`() {
        val vtt = """
            WEBVTT

            00:00.232 --> 00:01.516
            [UNKNOWN]: Thank you.

            00:13.263 --> 00:16.745
            [SPEAKER_00]: Welcome back.

            00:17.000 --> 00:18.000
            [Robby]: Hi.
        """.trimIndent()
        val t = Transcripts.parse(TranscriptFormat.VTT, vtt)!!
        assertEquals(listOf(null to "Thank you.", null to "Welcome back.", "Robby" to "Hi."), t.lines.map { it.speaker to it.text })
    }
}
