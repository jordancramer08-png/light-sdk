package com.jordan.watch.logic

/** One subtitle line (or lines) shown from [startMs] until [endMs]. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/** A sidecar subtitle file next to the video, e.g. "movie.en.srt" -> language "en". */
data class SidecarName(val fileName: String, val language: String?)

/** Reading .srt files, finding them next to a video, and the sync nudge. */
object Subtitles {

    const val NUDGE_STEP_MS = 100L
    const val NUDGE_MAX_MS = 10_000L

    /** Some .srt files start with this invisible mark (U+FEFF). */
    private val BYTE_ORDER_MARK = Char(0xFEFF)

    private val timeLine = Regex("""(\d+):(\d{1,2}):(\d{1,2})[,.](\d{1,3})\s*-->\s*(\d+):(\d{1,2}):(\d{1,2})[,.](\d{1,3})""")
    private val tags = Regex("""<[^>]+>|\{\\[^}]*\}""")

    /** Parses SubRip text. Broken blocks are skipped; never throws. Cues come out in time order. */
    fun parseSrt(text: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        val lines = text.removePrefix(BYTE_ORDER_MARK.toString()).replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < lines.size) {
            val match = timeLine.find(lines[i])
            if (match == null) {
                i++
                continue
            }
            val g = match.groupValues
            val start = ms(g[1], g[2], g[3], g[4])
            val end = ms(g[5], g[6], g[7], g[8])
            i++
            val body = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                body += lines[i].replace(tags, "").trim()
                i++
            }
            val joined = body.filter { it.isNotEmpty() }.joinToString("\n")
            if (joined.isNotEmpty() && end > start) cues += Cue(start, end, joined)
        }
        return cues.sortedBy { it.startMs }
    }

    /**
     * The text to show at [positionMs]. A positive [offsetMs] shows every line later, a
     * negative one earlier. "" when nothing is on screen.
     */
    fun textAt(cues: List<Cue>, positionMs: Long, offsetMs: Long): String {
        val t = positionMs - offsetMs
        return cues.filter { t >= it.startMs && t < it.endMs }.joinToString("\n") { it.text }
    }

    /** .srt files in [names] that belong to [videoName] ("movie.mp4" -> "movie.srt", "movie.en.srt"). */
    fun findSidecars(videoName: String, names: List<String>): List<SidecarName> {
        val base = videoName.substringBeforeLast('.')
        return names.filter { it.endsWith(".srt", ignoreCase = true) && it.startsWith(base, ignoreCase = true) }
            .sorted()
            .map { name ->
                val middle = name.substring(base.length, name.length - 4).trim('.')
                SidecarName(name, middle.ifEmpty { null })
            }
    }

    /** One nudge step later (+1) or earlier (-1), kept within ±10 s. */
    fun nudge(offsetMs: Long, direction: Int): Long =
        (offsetMs + direction * NUDGE_STEP_MS).coerceIn(-NUDGE_MAX_MS, NUDGE_MAX_MS)

    /** 300 -> "0.3 s later", -1200 -> "1.2 s earlier", 0 -> "In sync". */
    fun nudgeLabel(offsetMs: Long): String {
        if (offsetMs == 0L) return "In sync"
        val tenths = kotlin.math.abs(offsetMs) / 100
        val word = if (offsetMs > 0) "later" else "earlier"
        return "${tenths / 10}.${tenths % 10} s $word"
    }

    /** "en" -> "English"; unknown codes come back in capitals; null -> null. */
    fun languageName(code: String?): String? {
        val c = code?.lowercase()?.substringBefore('-')?.ifBlank { null } ?: return null
        return LANGUAGES[c] ?: c.uppercase()
    }

    /** A track's name for the pickers: its label, else its language, else "Track 2". */
    fun trackName(label: String?, language: String?, number: Int): String =
        label?.ifBlank { null } ?: languageName(language)?.takeIf { it != "UND" } ?: "Track $number"

    /** The size setting -> which of four text sizes to use (0 smallest). */
    fun sizeStep(setting: String): Int = when (setting) {
        "SMALL" -> 0
        "LARGE" -> 2
        "XLARGE" -> 3
        else -> 1
    }

    private fun ms(h: String, m: String, s: String, frac: String): Long =
        h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + frac.padEnd(3, '0').toLong()

    private val LANGUAGES = mapOf(
        "en" to "English", "eng" to "English", "es" to "Spanish", "spa" to "Spanish",
        "fr" to "French", "fre" to "French", "fra" to "French", "de" to "German", "ger" to "German",
        "deu" to "German", "it" to "Italian", "ita" to "Italian", "pt" to "Portuguese",
        "por" to "Portuguese", "ja" to "Japanese", "jpn" to "Japanese", "ko" to "Korean",
        "kor" to "Korean", "zh" to "Chinese", "chi" to "Chinese", "zho" to "Chinese",
        "ru" to "Russian", "rus" to "Russian", "nl" to "Dutch", "dut" to "Dutch",
    )
}

/**
 * Embedded subtitles arrive from the player at their own time, so they can only be shown
 * LATER, not earlier: this remembers the last few seconds of text by position.
 */
class DelayedText {
    private val history = ArrayDeque<Pair<Long, String>>()

    /** Call whenever the player's subtitle text or position changes. */
    fun record(positionMs: Long, text: String) {
        val last = history.lastOrNull()
        if (last != null && positionMs < last.first) history.clear()
        if (last?.second != text || history.isEmpty()) history.addLast(positionMs to text)
        while (history.size > 1 && history[1].first < positionMs - Subtitles.NUDGE_MAX_MS) history.removeFirst()
    }

    /** What was on screen [delayMs] ago (delay 0 or less = now). */
    fun textAt(positionMs: Long, delayMs: Long): String {
        if (delayMs <= 0) return history.lastOrNull()?.second ?: ""
        val t = positionMs - delayMs
        return history.lastOrNull { it.first <= t }?.second ?: ""
    }

    fun clear() = history.clear()
}
