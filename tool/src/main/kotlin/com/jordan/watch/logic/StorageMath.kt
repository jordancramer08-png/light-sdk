package com.jordan.watch.logic

import kotlin.math.roundToLong

/** Sizes, the 50 GB cap, and "1080p H.264 AAC · 2.1 GB" lines. */
object StorageMath {

    const val GB = 1_000_000_000L
    const val MB = 1_000_000L
    const val CAP_BYTES = 50 * GB

    /** Bits per second the PC script aims for when it shrinks a file (video + audio). */
    const val BPS_720P = 2_000_000L
    const val BPS_1080P = 4_000_000L

    fun totalBytes(items: List<LibraryItem>): Long = items.sumOf { it.bytes ?: 0L }

    /** Space left under the cap, never negative. */
    fun roomLeft(usedBytes: Long, capBytes: Long = CAP_BYTES): Long = (capBytes - usedBytes).coerceAtLeast(0)

    /** Adding [addBytes] must stay under the cap AND fit in the phone's free space. */
    fun fits(usedBytes: Long, addBytes: Long, freeBytes: Long, capBytes: Long = CAP_BYTES): Boolean =
        usedBytes + addBytes <= capBytes && addBytes <= freeBytes

    /** Expected size of a [runtimeMin]-minute title at [bitsPerSecond]. */
    fun estimateBytes(runtimeMin: Int, bitsPerSecond: Long): Long = runtimeMin * 60L * bitsPerSecond / 8

    /** 0–100, how full the cap is. */
    fun percentUsed(usedBytes: Long, capBytes: Long = CAP_BYTES): Int {
        if (capBytes <= 0) return 100
        return ((usedBytes * 100.0) / capBytes).roundToLong().coerceIn(0, 100).toInt()
    }

    /** 2_100_000_000 -> "2.1 GB"; 734_000_000 -> "734 MB". Decimal units, as Windows shows drives. */
    fun formatBytes(bytes: Long): String = when {
        bytes >= GB -> "${oneDecimal(bytes.toDouble() / GB)} GB"
        bytes >= MB -> "${(bytes.toDouble() / MB).roundToLong()} MB"
        bytes > 0 -> "${(bytes / 1000).coerceAtLeast(1)} KB"
        else -> "0 KB"
    }

    /** Settings line: "Watch uses 23.4 GB of its 50 GB · 12.1 GB free on the phone". */
    fun usageLine(usedBytes: Long, freeBytes: Long?, capBytes: Long = CAP_BYTES): String {
        val used = "Watch uses ${formatBytes(usedBytes)} of its ${formatBytes(capBytes)} (${percentUsed(usedBytes, capBytes)}%)"
        return if (freeBytes == null || freeBytes < 0) used else "$used · ${formatBytes(freeBytes)} free on the phone"
    }

    /** "1080p H.264 AAC · 2.1 GB"; unknown parts are left out. */
    fun qualityLine(height: Int?, videoCodec: String?, audioCodec: String?, bytes: Long?): String {
        val parts = listOfNotNull(
            height?.takeIf { it > 0 }?.let { if (it >= 2000) "4K" else "${it}p" },
            videoCodec?.let(::codecName),
            audioCodec?.let(::codecName),
        )
        val size = bytes?.takeIf { it > 0 }?.let(::formatBytes)
        return listOfNotNull(parts.joinToString(" ").ifEmpty { null }, size).joinToString(" · ")
    }

    private fun codecName(codec: String): String = when (codec.lowercase()) {
        "h264", "avc", "avc1" -> "H.264"
        "hevc", "h265", "hvc1" -> "HEVC"
        "aac" -> "AAC"
        "ac3" -> "AC3"
        "eac3" -> "E-AC3"
        "opus" -> "Opus"
        "mp3" -> "MP3"
        else -> codec.uppercase()
    }

    private fun oneDecimal(value: Double): String {
        val tenths = (value * 10).roundToLong()
        return "${tenths / 10}.${tenths % 10}"
    }
}
