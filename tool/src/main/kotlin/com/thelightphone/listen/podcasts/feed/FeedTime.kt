package com.thelightphone.listen.podcasts.feed

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Feed dates and lengths, which come in many shapes. Nothing here throws. */
object FeedTime {

    /**
     * A publish date as ms since 1970, or null when it can't be read. Handles RFC 822 as it
     * appears in the wild: with or without the weekday (a wrong weekday is ignored), one- or
     * two-digit days, full or short month names, two-digit years, missing seconds, zone
     * names (GMT, EST, PDT…) or offsets (+0000, -05:00), or no zone (taken as UTC). Also
     * ISO 8601 ("2026-10-01T09:30:00Z", "2026-10-01").
     */
    fun parseDate(text: String?): Long? {
        val s = text?.trim()?.replace(SPACES_RE, " ")?.takeIf { it.isNotEmpty() } ?: return null
        return parseRfc822(s) ?: parseUs(s) ?: parseIso(s)
    }

    private fun parseRfc822(s: String): Long? {
        // Drop a leading weekday ("Tue,", "Tuesday ", "Tue.").
        val body = s.replace(WEEKDAY_RE, "")
        val m = RFC_RE.matchEntire(body) ?: return null
        val day = m.groupValues[1].toInt()
        val month = monthNumber(m.groupValues[2]) ?: return null
        var year = m.groupValues[3].toInt()
        if (m.groupValues[3].length == 2) year += if (year < 70) 2000 else 1900
        val hour = m.groupValues[4].toIntOrNull() ?: 0
        val minute = m.groupValues[5].toIntOrNull() ?: 0
        val second = m.groupValues[6].toIntOrNull() ?: 0
        val offset = zoneOffset(m.groupValues[7]) ?: ZoneOffset.UTC
        return try {
            LocalDateTime.of(year, month, day, hour.coerceAtMost(23), minute, second.coerceAtMost(59))
                .toInstant(offset).toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    /** "October 1, 2026", "Tuesday, Oct 1st 2026 9:30 PM EST". */
    private fun parseUs(s: String): Long? {
        val m = US_RE.matchEntire(s) ?: return null
        val month = monthNumber(m.groupValues[1]) ?: return null
        val day = m.groupValues[2].toInt()
        val year = m.groupValues[3].toInt()
        var hour = m.groupValues[4].toIntOrNull() ?: 0
        val minute = m.groupValues[5].toIntOrNull() ?: 0
        val second = m.groupValues[6].toIntOrNull() ?: 0
        when (m.groupValues[7].uppercase()) {
            "PM" -> if (hour < 12) hour += 12
            "AM" -> if (hour == 12) hour = 0
        }
        val offset = zoneOffset(m.groupValues[8]) ?: ZoneOffset.UTC
        return try {
            LocalDateTime.of(year, month, day, hour.coerceAtMost(23), minute, second.coerceAtMost(59))
                .toInstant(offset).toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    private fun parseIso(s: String): Long? {
        try {
            return OffsetDateTime.parse(s.replace(' ', 'T')).toInstant().toEpochMilli()
        } catch (_: Exception) {
        }
        try {
            return LocalDateTime.parse(s.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: Exception) {
        }
        try {
            return LocalDate.parse(s.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: Exception) {
        }
        return null
    }

    private fun monthNumber(name: String): Int? {
        val key = name.lowercase().take(3)
        val i = MONTHS.indexOf(key)
        return if (i < 0) null else i + 1
    }

    private fun zoneOffset(zone: String): ZoneOffset? {
        val z = zone.trim().uppercase()
        if (z.isEmpty()) return null
        NAMED_ZONES[z]?.let { return ZoneOffset.ofHours(it) }
        val m = OFFSET_RE.matchEntire(z) ?: return null
        val sign = if (m.groupValues[1] == "-") -1 else 1
        val hours = m.groupValues[2].toInt()
        val minutes = m.groupValues[3].toIntOrNull() ?: 0
        if (hours > 18 || minutes > 59) return null
        return ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes)
    }

    /**
     * An itunes:duration as ms: "3600" (seconds), "3600.5", "60:00", "1:02:03", "01:02:03.250".
     * Null when missing, zero or unreadable.
     */
    fun parseDuration(text: String?): Long? {
        val s = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = s.split(':')
        if (parts.size > 3) return null
        var seconds = 0.0
        for (p in parts) {
            val v = p.trim().toDoubleOrNull() ?: return null
            if (v < 0 || v.isNaN() || v.isInfinite()) return null
            seconds = seconds * 60 + v
        }
        val ms = (seconds * 1000).toLong()
        return ms.takeIf { it > 0 && it < MAX_DURATION_MS }
    }

    /**
     * A clock time inside an episode as ms: "1:02:03.5", "02:03", "62.5", "00:01:02,500"
     * (SRT uses a comma). Used for chapters and transcripts. Null when unreadable.
     */
    fun parseClock(text: String?): Long? {
        val s = text?.trim()?.replace(',', '.')?.takeIf { it.isNotEmpty() } ?: return null
        val parts = s.split(':')
        if (parts.size > 3) return null
        var seconds = 0.0
        for (p in parts) {
            val v = p.trim().toDoubleOrNull() ?: return null
            if (v < 0 || v.isNaN() || v.isInfinite()) return null
            seconds = seconds * 60 + v
        }
        return (seconds * 1000 + 0.5).toLong().takeIf { it < MAX_DURATION_MS }
    }

    /** Longer than any real episode (100 hours); anything above is junk. */
    private const val MAX_DURATION_MS = 100L * 3600 * 1000

    private val SPACES_RE = Regex("""\s+""")
    private val WEEKDAY_RE = Regex("""^[A-Za-z]{3,9}\.?,?\s*""")
    private val RFC_RE = Regex(
        """^(\d{1,2})[\s-]+([A-Za-z]{3,9})\.?,?[\s-]+(\d{4}|\d{2})""" +
            """(?:[\sT,]+(\d{1,2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?)?\s*([A-Za-z]{1,5}|[+-]\d{2}:?\d{2}|[+-]\d{1,2})?\s*$""",
    )
    private val US_RE = Regex(
        """^(?:[A-Za-z]+,\s*)?([A-Za-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})""" +
            """(?:[\s,]+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(AM|PM|am|pm)?)?\s*([A-Za-z]{1,5}|[+-]\d{2}:?\d{2})?\s*$""",
    )
    private val OFFSET_RE = Regex("""^([+-])(\d{1,2}):?(\d{2})?$""")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val NAMED_ZONES = mapOf(
        "GMT" to 0, "UT" to 0, "UTC" to 0, "Z" to 0, "BST" to 1, "CET" to 1, "CEST" to 2,
        "EST" to -5, "EDT" to -4, "CST" to -6, "CDT" to -5, "MST" to -7, "MDT" to -6,
        "PST" to -8, "PDT" to -7, "AKST" to -9, "AKDT" to -8, "HST" to -10, "AEST" to 10, "AEDT" to 11,
    )
}
