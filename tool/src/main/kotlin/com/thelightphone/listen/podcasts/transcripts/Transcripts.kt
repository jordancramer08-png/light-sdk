package com.thelightphone.listen.podcasts.transcripts

import com.thelightphone.listen.podcasts.feed.FeedTime
import com.thelightphone.listen.podcasts.xml.HtmlToText
import com.thelightphone.listen.podcasts.xml.MarkupReader
import com.thelightphone.listen.podcasts.xml.MarkupToken
import com.thelightphone.listen.podcasts.xml.decodeEntities
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * One line of a transcript: its [text], who said it when known, and when it starts and ends
 * (ms into the episode) for timed formats. Untimed transcripts have null times.
 */
@Serializable
data class TranscriptLine(
    val text: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val speaker: String? = null,
)

/** A whole transcript in the one format the Transcript screen reads. */
@Serializable
data class Transcript(val lines: List<TranscriptLine>) {
    /** True when the lines have start times, so the screen can follow along and jump. */
    val timed: Boolean get() = lines.isNotEmpty() && lines.count { it.startMs != null } * 2 >= lines.size

    /**
     * The index of the line playing at [positionMs] (the last line starting at or before it),
     * or -1 before the first line or when untimed. Binary search, so it's cheap every 250 ms.
     */
    fun lineAt(positionMs: Long): Int {
        if (!timed) return -1
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val start = lines[mid].startMs ?: startBefore(mid)
            if (start <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    private fun startBefore(i: Int): Long {
        for (j in i downTo 0) lines[j].startMs?.let { return it }
        return 0
    }
}

/** The transcript formats PODCASTS.md lists, best (timed) first. */
enum class TranscriptFormat { VTT, SRT, JSON, HTML, TEXT }

/** Transcript files bigger than this aren't read (PODCAST_PLAN.md §5). */
const val MAX_TRANSCRIPT_BYTES = 5_000_000

/** Lines longer than this are split; word-level JSON segments are joined up to about this long. */
private const val LONG_LINE_CHARS = 280

object Transcripts {

    /**
     * The format of a `podcast:transcript` from its type (or, when the type is missing or
     * vague, its address's ending). Null when it isn't a format Listen reads.
     */
    fun formatOf(type: String?, url: String?): TranscriptFormat? {
        val t = type?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        when (t) {
            "text/vtt", "text/webvtt" -> return TranscriptFormat.VTT
            "application/x-subrip", "application/srt", "text/srt", "application/x-srt", "text/x-srt" -> return TranscriptFormat.SRT
            "application/json", "text/json", "application/transcript+json" -> return TranscriptFormat.JSON
            "text/html", "application/xhtml+xml" -> return TranscriptFormat.HTML
            "text/plain" -> return TranscriptFormat.TEXT
        }
        val path = url?.lowercase()?.substringBefore('?').orEmpty()
        return when {
            path.endsWith(".vtt") -> TranscriptFormat.VTT
            path.endsWith(".srt") -> TranscriptFormat.SRT
            path.endsWith(".json") -> TranscriptFormat.JSON
            path.endsWith(".html") || path.endsWith(".htm") -> TranscriptFormat.HTML
            path.endsWith(".txt") -> TranscriptFormat.TEXT
            else -> null
        }
    }

    /** Reads [text] as [format]. Null when nothing readable comes out. Never throws. */
    fun parse(format: TranscriptFormat, text: String): Transcript? {
        if (text.length > MAX_TRANSCRIPT_BYTES) return null
        val clean = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val lines = try {
            when (format) {
                TranscriptFormat.VTT -> parseVtt(clean)
                TranscriptFormat.SRT -> parseSrt(clean)
                TranscriptFormat.JSON -> parseJson(clean)
                TranscriptFormat.HTML -> parseHtml(clean)
                TranscriptFormat.TEXT -> parseText(clean)
            }
        } catch (e: Exception) {
            emptyList()
        }
        return lines.filter { it.text.isNotBlank() }.takeIf { it.isNotEmpty() }?.let(::Transcript)
    }

    // ---- WebVTT ----

    private val CUE_TIME_RE = Regex("""^\s*([\d:.,]+)\s+-->\s+([\d:.,]+)""")
    private val VOICE_RE = Regex("""<v(?:\.[^\s>]*)?\s+([^>]+)>""")

    private fun parseVtt(text: String): List<TranscriptLine> {
        val out = mutableListOf<TranscriptLine>()
        for (block in text.split(BLANK_LINES_RE)) {
            val rows = block.split('\n').filter { it.isNotBlank() }
            val timeRow = rows.indexOfFirst { CUE_TIME_RE.containsMatchIn(it) }
            if (timeRow < 0) continue // WEBVTT header, NOTE, STYLE, REGION
            val m = CUE_TIME_RE.find(rows[timeRow])!!
            val body = rows.drop(timeRow + 1).joinToString(" ")
            val speaker = VOICE_RE.find(body)?.groupValues?.get(1)?.trim()
            out += TranscriptLine(
                text = cueText(body),
                startMs = FeedTime.parseClock(m.groupValues[1]),
                endMs = FeedTime.parseClock(m.groupValues[2]),
                speaker = speaker,
            )
        }
        return out
    }

    // ---- SRT ----

    private fun parseSrt(text: String): List<TranscriptLine> {
        val out = mutableListOf<TranscriptLine>()
        for (block in text.split(BLANK_LINES_RE)) {
            val rows = block.split('\n').filter { it.isNotBlank() }
            val timeRow = rows.indexOfFirst { CUE_TIME_RE.containsMatchIn(it) }
            if (timeRow < 0) continue
            val m = CUE_TIME_RE.find(rows[timeRow])!!
            var body = cueText(rows.drop(timeRow + 1).joinToString(" "))
            var speaker: String? = null
            SPEAKER_PREFIX_RE.find(body)?.let {
                speaker = it.groupValues[1].trim()
                body = body.substring(it.range.last + 1).trim()
            }
            out += TranscriptLine(body, FeedTime.parseClock(m.groupValues[1]), FeedTime.parseClock(m.groupValues[2]), speaker)
        }
        return out
    }

    /** Cue text without markup ("<i>", "<v Bob>", "<00:01.000>") and with entities decoded. */
    private fun cueText(body: String): String =
        decodeEntities(body.replace(CUE_TAG_RE, "")).replace(SPACES_RE, " ").trim()

    // ---- Podcasting 2.0 JSON ----

    private val json = Json { isLenient = true }

    /**
     * `{"version":"1.0.0","segments":[{"speaker":"Ann","startTime":0.5,"endTime":1.2,"body":"Hi"}]}`.
     * Many files give one word per segment, so segments by the same speaker are joined into
     * readable lines, broken at the end of a sentence once a line is long enough, at a
     * change of speaker, or at a pause of more than 2 seconds.
     */
    private fun parseJson(text: String): List<TranscriptLine> {
        val root = json.parseToJsonElement(text)
        val segments = when (root) {
            is JsonObject -> root["segments"] as? JsonArray
            is JsonArray -> root
            else -> null
        } ?: return emptyList()
        val out = mutableListOf<TranscriptLine>()
        var current: TranscriptLine? = null
        for (element in segments) {
            val o = element as? JsonObject ?: continue
            val body = ((o["body"] ?: o["text"]) as? JsonPrimitive)?.contentOrNull?.let { decodeEntities(it).trim() }
                ?.takeIf { it.isNotEmpty() } ?: continue
            val start = (o["startTime"] as? JsonPrimitive)?.doubleOrNull?.let { (it * 1000).toLong() }
            val end = (o["endTime"] as? JsonPrimitive)?.doubleOrNull?.let { (it * 1000).toLong() }
            val speaker = (o["speaker"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val c = current
            val gap = if (c?.endMs != null && start != null) start - c.endMs else 0
            val endsSentence = c != null && c.text.length >= 80 && c.text.last() in ".?!"
            if (c != null && (speaker ?: c.speaker) == c.speaker && gap <= 2_000 && !endsSentence && c.text.length < LONG_LINE_CHARS) {
                current = c.copy(text = joinWords(c.text, body), endMs = end ?: c.endMs)
            } else {
                c?.let { out += it }
                current = TranscriptLine(body, start, end, speaker)
            }
        }
        current?.let { out += it }
        return out
    }

    private fun joinWords(a: String, b: String): String =
        if (b.first() in ".,!?;:'’)" || a.endsWith(" ")) a + b else "$a $b"

    // ---- HTML ----

    /**
     * Podcast HTML transcripts are usually paragraphs, sometimes with `<cite>Speaker:</cite>`
     * and `<time>00:01:02</time>`. A paragraph that starts with a time (in a `<time>` tag or
     * as text, like "[00:01:02]") becomes a timed line.
     */
    private fun parseHtml(html: String): List<TranscriptLine> {
        val paragraphs = mutableListOf<String>()
        val sb = StringBuilder()
        var skip = 0
        fun flush() {
            if (sb.isNotBlank()) paragraphs += sb.toString()
            sb.setLength(0)
        }
        for (token in MarkupReader.tokens(html)) {
            when (token) {
                is MarkupToken.StartTag -> when {
                    token.name in setOf("script", "style", "head") -> if (!token.selfClosing) skip++
                    token.name in HTML_BLOCKS -> flush()
                    token.name == "br" -> sb.append(' ')
                    token.name == "time" -> sb.append(' ')
                }
                is MarkupToken.EndTag -> when {
                    token.name in setOf("script", "style", "head") -> if (skip > 0) skip--
                    token.name in HTML_BLOCKS -> flush()
                    token.name == "time" || token.name == "cite" -> sb.append(' ')
                }
                is MarkupToken.Text -> if (skip == 0) sb.append(token.text)
            }
        }
        flush()
        // Speaker and time often sit in their own paragraphs before the words: join them on.
        val out = mutableListOf<TranscriptLine>()
        var pendingSpeaker: String? = null
        var pendingStart: Long? = null
        for (p in paragraphs) {
            val line = timedLine(p.replace(SPACES_RE, " ").trim())
            if (line.text.isEmpty() || (line.text.endsWith(":") && line.text.length < 40)) {
                if (line.text.isNotEmpty()) pendingSpeaker = line.text.removeSuffix(":").trim()
                line.startMs?.let { pendingStart = it }
                line.speaker?.let { pendingSpeaker = it }
                continue
            }
            out += line.copy(
                startMs = line.startMs ?: pendingStart,
                speaker = line.speaker ?: pendingSpeaker,
            )
            pendingSpeaker = null
            pendingStart = null
        }
        return out
    }

    // ---- Plain text ----

    /** Paragraphs (or lines, when there are no blank lines); a leading time makes a timed line. */
    private fun parseText(text: String): List<TranscriptLine> {
        val chunks = if (BLANK_LINES_RE.containsMatchIn(text)) text.split(BLANK_LINES_RE) else text.split('\n')
        return chunks.map { timedLine(it.replace(SPACES_RE, " ").trim()) }.flatMap(::splitLong)
    }

    /**
     * "[00:01:02] Ann: Hello", "00:01:02 Hello", "Ann (01:02): Hello", "Ann 01:02 Hello" →
     * a line with its time and speaker. Text without a leading time stays untimed.
     */
    private fun timedLine(text: String): TranscriptLine {
        LEADING_TIME_RE.find(text)?.let { m ->
            val start = FeedTime.parseClock(m.groupValues[1])
            var rest = text.substring(m.range.last + 1).trim()
            var speaker: String? = null
            SPEAKER_PREFIX_RE.find(rest)?.let {
                speaker = it.groupValues[1].trim()
                rest = rest.substring(it.range.last + 1).trim()
            }
            return TranscriptLine(rest, start, null, speaker)
        }
        SPEAKER_TIME_RE.find(text)?.let { m ->
            return TranscriptLine(text.substring(m.range.last + 1).trim(), FeedTime.parseClock(m.groupValues[2]), null, m.groupValues[1].trim())
        }
        return TranscriptLine(HtmlToText.inline(text).ifEmpty { text })
    }

    /** Very long untimed paragraphs are split at sentence ends, so one row never fills screens. */
    private fun splitLong(line: TranscriptLine): List<TranscriptLine> {
        if (line.text.length <= LONG_LINE_CHARS * 3 || line.startMs != null) return listOf(line)
        val out = mutableListOf<TranscriptLine>()
        val sb = StringBuilder()
        for (sentence in line.text.split(SENTENCE_END_RE)) {
            if (sb.isNotEmpty() && sb.length + sentence.length > LONG_LINE_CHARS) {
                out += line.copy(text = sb.toString().trim())
                sb.setLength(0)
            }
            sb.append(sentence).append(' ')
        }
        if (sb.isNotBlank()) out += line.copy(text = sb.toString().trim())
        return out
    }

    private val BLANK_LINES_RE = Regex("""\n\s*\n""")
    private val SPACES_RE = Regex("""\s+""")
    private val CUE_TAG_RE = Regex("""</?[a-zA-Z][^>]*>|<\d[\d:.]*>""")
    private val SPEAKER_PREFIX_RE = Regex("""^([A-Z][\w.'’ -]{0,30}):\s+""")
    private val LEADING_TIME_RE = Regex("""^[\[(]?((?:\d{1,2}:)?\d{1,2}:\d{2}(?:[.,]\d{1,3})?)[\])]?\s*[-–—]?\s*""")
    private val SPEAKER_TIME_RE = Regex("""^([A-Z][\w.'’ -]{0,30}?):?\s*[\[(]?((?:\d{1,2}:)?\d{1,2}:\d{2})[\])]?:?(?:\s+|$)""")
    private val SENTENCE_END_RE = Regex("""(?<=[.!?])\s+""")
    private val HTML_BLOCKS = setOf("p", "div", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "section", "dt", "dd")
}
