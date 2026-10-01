package com.thelightphone.listen.playback

import com.thelightphone.sdk.audio.LightAudioFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FormatLineTest {

    @Test
    fun `the player's own values come first`() {
        val mp3 = LightAudioFormat(mimeType = "audio/mpeg", bitrate = 190_400, sampleRate = 44_100)
        assertEquals("MP3 · 190 kbps · 44.1 kHz", formatLine(mp3, "a.mp3", 9_000_000, 240_000))
    }

    @Test
    fun `a missing bitrate is worked out from the file size and length`() {
        // 30 MB over 4 minutes is 1,000 kbps.
        val flac = LightAudioFormat(mimeType = "audio/flac", sampleRate = 96_000)
        assertEquals("FLAC · 1000 kbps · 96 kHz", formatLine(flac, "a.flac", 30_000_000, 240_000))
    }

    @Test
    fun `codecs get their everyday names`() {
        assertEquals("AAC · 48 kHz", formatLine(LightAudioFormat("audio/mp4a-latm", sampleRate = 48_000), "a.m4b", 0, 0))
        assertEquals("WAV", formatLine(LightAudioFormat("audio/raw", containerMimeType = "audio/wav"), "a.wav", 0, 0))
        assertEquals("Ogg Vorbis", formatLine(LightAudioFormat("audio/vorbis"), "a.ogg", 0, 0))
        assertEquals("Opus", formatLine(LightAudioFormat("audio/opus"), "a.opus", 0, 0))
    }

    @Test
    fun `before the player says anything, only a full guess is shown`() {
        assertNull(formatLine(null, "a.mp3", 0, 0))
        assertEquals("MP3 · 256 kbps", formatLine(null, "a.mp3", 7_680_000, 240_000))
    }
}
