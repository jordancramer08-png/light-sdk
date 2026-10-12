package com.jordan.watch.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerExtrasTest {

    @Test
    fun aspectFitFillZoomAndPinch() {
        // 1920x1080 video in a 1080x1240 portrait box.
        val fit = Aspect.pictureSize(1080f, 1240f, 1920, 1080, AspectMode.FIT, 1f)
        assertEquals(1080f, fit.first, 0.5f)
        assertEquals(607.5f, fit.second, 0.5f)
        val fill = Aspect.pictureSize(1080f, 1240f, 1920, 1080, AspectMode.FILL, 1f)
        assertEquals(1240f, fill.second, 0.5f)
        val zoom = Aspect.pictureSize(1080f, 1240f, 1920, 1080, AspectMode.ZOOM, 1f)
        assertTrue(zoom.second > fill.second)
        val pinched = Aspect.pictureSize(1080f, 1240f, 1920, 1080, AspectMode.FIT, 10f)
        assertEquals(1080f * 4, pinched.first, 1f)
        assertEquals(1080f to 1240f, Aspect.pictureSize(1080f, 1240f, 0, 0, AspectMode.FIT, 1f))
        assertEquals(AspectMode.FILL, Aspect.next(AspectMode.FIT))
        assertEquals(AspectMode.FIT, Aspect.next(AspectMode.ZOOM))
        assertEquals(AspectMode.FIT, Aspect.fromName("nonsense"))
    }

    @Test
    fun srtParsesAndTimes() {
        val srt = Char(0xFEFF) + "1\r\n00:00:01,000 --> 00:00:02,500\r\n<i>Hello</i>\r\nthere\r\n\r\n" +
            "2\r\n00:00:03,000 --> 00:00:04,000\r\n{\\an8}Second\r\n\r\nbroken block\r\n"
        val cues = Subtitles.parseSrt(srt)
        assertEquals(2, cues.size)
        assertEquals(Cue(1000, 2500, "Hello\nthere"), cues[0])
        assertEquals("Second", cues[1].text)
        assertEquals("Hello\nthere", Subtitles.textAt(cues, 1500, 0))
        assertEquals("", Subtitles.textAt(cues, 2600, 0))
        // 500 ms later: the first line now shows until 3.0 s.
        assertEquals("Hello\nthere", Subtitles.textAt(cues, 2900, 500))
        // 500 ms earlier: the second line starts at 2.5 s.
        assertEquals("Second", Subtitles.textAt(cues, 2600, -500))
        assertTrue(Subtitles.parseSrt("garbage").isEmpty())
    }

    @Test
    fun sidecarsAndNames() {
        val found = Subtitles.findSidecars("movie.mp4", listOf("movie.json", "movie.en.srt", "movie.srt", "poster.jpg", "other.srt"))
        assertEquals(listOf(SidecarName("movie.en.srt", "en"), SidecarName("movie.srt", null)), found)
        assertEquals("English", Subtitles.languageName("en"))
        assertEquals("XX", Subtitles.languageName("xx"))
        assertEquals("Commentary", Subtitles.trackName("Commentary", "en", 2))
        assertEquals("Track 3", Subtitles.trackName(null, null, 3))
    }

    @Test
    fun nudgeStepsAndLabels() {
        assertEquals(100L, Subtitles.nudge(0, 1))
        assertEquals(-10_000L, Subtitles.nudge(-10_000, -1))
        assertEquals("In sync", Subtitles.nudgeLabel(0))
        assertEquals("0.3 s later", Subtitles.nudgeLabel(300))
        assertEquals("1.2 s earlier", Subtitles.nudgeLabel(-1200))
    }

    @Test
    fun delayedTextHoldsLinesBack() {
        val d = DelayedText()
        d.record(1000, "A")
        d.record(2000, "")
        d.record(3000, "B")
        assertEquals("B", d.textAt(3000, 0))
        assertEquals("A", d.textAt(2500, 1000))
        assertEquals("", d.textAt(500, 200))
        d.record(100, "C") // seeked back: history starts again
        assertEquals("C", d.textAt(100, 0))
    }

    @Test
    fun sleepTimerAndCountdown() {
        assertNull(SleepTimer.endsAt(null, 0))
        assertNull(SleepTimer.endsAt(SleepTimer.END_OF_TITLE, 0))
        assertEquals(15 * 60_000L, SleepTimer.endsAt(15, 0))
        assertTrue(SleepTimer.isDue(1000, 1000))
        assertFalse(SleepTimer.isDue(null, 1000))
        assertEquals("Sleep 12m", SleepTimer.buttonText(15, 12 * 60_000L, 1))
        assertEquals("Sleep: end", SleepTimer.buttonText(SleepTimer.END_OF_TITLE, null, 0))
        assertEquals(10, NextUpCountdown.secondsLeft(0, 0))
        assertEquals(7, NextUpCountdown.secondsLeft(0, 3_200))
        assertEquals(0, NextUpCountdown.secondsLeft(0, 60_000))
        assertTrue(NextUpCountdown.shouldOffer(true, 30))
        assertFalse(NextUpCountdown.shouldOffer(true, SleepTimer.END_OF_TITLE))
        assertFalse(NextUpCountdown.shouldOffer(false, null))
    }

    @Test
    fun techDetailsLines() {
        assertEquals("H.264", TechDetails.codecName("video/avc"))
        assertEquals("XYZ", TechDetails.codecName("video/xyz"))
        assertEquals("1920 × 800 (1080p)", TechDetails.resolution(1920, 800))
        assertEquals("1280 × 720 (720p)", TechDetails.resolution(1280, 720))
        assertEquals("4.2 Mbps", TechDetails.bitrate(4_200_000))
        assertEquals("800 kbps", TechDetails.bitrate(800_000))
        assertEquals(8_000L, TechDetails.averageBitrate(1000, 1000))
        val lines = TechDetails.lines("video/avc", 1920, 1080, null, null, "audio/mp4a-latm", 2_100_000_000, 7_200_000)
        assertEquals(listOf("Video: H.264", "Picture: 1920 × 1080 (1080p)", "File bitrate: 2.3 Mbps (average)", "Audio: AAC", "File size: 2.1 GB"), lines)
    }

    @Test
    fun storageUsageLine() {
        assertEquals(
            "Watch uses 23.4 GB of its 50.0 GB (47%) · 12.1 GB free on the phone",
            StorageMath.usageLine(23_400_000_000, 12_100_000_000),
        )
        assertEquals("Watch uses 0 KB of its 50.0 GB (0%)", StorageMath.usageLine(0, null))
    }

    @Test
    fun skipAheadIsNinetySeconds() {
        assertEquals(190_000L, PlayerMath.skip(100_000, PlayerMath.SKIP_AHEAD_MS, 0))
        assertEquals(150_000L, PlayerMath.skip(100_000, PlayerMath.SKIP_AHEAD_MS, 150_000))
    }
}
