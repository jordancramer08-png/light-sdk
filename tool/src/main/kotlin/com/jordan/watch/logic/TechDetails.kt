package com.jordan.watch.logic

import kotlin.math.roundToLong

/** The player's "Details" overlay lines: codecs, resolution, bitrate, file size. */
object TechDetails {

    /** "video/avc" -> "H.264"; unknown types keep their last part in capitals. */
    fun codecName(mime: String?): String? {
        val m = mime?.lowercase()?.ifBlank { null } ?: return null
        return CODECS[m] ?: m.substringAfter('/').uppercase()
    }

    /** "1920 × 1080 (1080p)", or null when unknown. */
    fun resolution(width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        val name = when {
            height >= 2000 || width >= 3800 -> "4K"
            width >= 1900 -> "1080p"
            width >= 1260 -> "720p"
            else -> "${height}p"
        }
        return "$width × $height ($name)"
    }

    /** 4_200_000 -> "4.2 Mbps"; 800_000 -> "800 kbps". */
    fun bitrate(bitsPerSecond: Long): String? = when {
        bitsPerSecond <= 0 -> null
        bitsPerSecond >= 1_000_000 -> {
            val tenths = (bitsPerSecond / 100_000.0).roundToLong()
            "${tenths / 10}.${tenths % 10} Mbps"
        }
        else -> "${(bitsPerSecond / 1000.0).roundToLong()} kbps"
    }

    /** The whole file's average bits per second, from its size and length. */
    fun averageBitrate(fileBytes: Long, durationMs: Long): Long =
        if (fileBytes <= 0 || durationMs <= 0) 0 else fileBytes * 8 * 1000 / durationMs

    /** Lines for the overlay; unknown things are left out. */
    fun lines(
        videoCodec: String?, width: Int, height: Int, videoBitrate: Int?, frameRate: Float?,
        audioCodec: String?, fileBytes: Long, durationMs: Long,
    ): List<String> = listOfNotNull(
        codecName(videoCodec)?.let { "Video: $it" },
        resolution(width, height)?.let { "Picture: $it" },
        frameRate?.takeIf { it > 0f }?.let { "Frame rate: ${(it * 100).roundToLong() / 100.0} fps" },
        videoBitrate?.toLong()?.let(::bitrate)?.let { "Video bitrate: $it" },
        bitrate(averageBitrate(fileBytes, durationMs))?.let { "File bitrate: $it (average)" },
        codecName(audioCodec)?.let { "Audio: $it" },
        fileBytes.takeIf { it > 0 }?.let { "File size: ${StorageMath.formatBytes(it)}" },
    )

    private val CODECS = mapOf(
        "video/avc" to "H.264", "video/hevc" to "HEVC (H.265)", "video/x-vnd.on2.vp9" to "VP9",
        "video/av01" to "AV1", "video/mp4v-es" to "MPEG-4", "video/mpeg2" to "MPEG-2",
        "audio/mp4a-latm" to "AAC", "audio/ac3" to "AC3", "audio/eac3" to "E-AC3",
        "audio/opus" to "Opus", "audio/mpeg" to "MP3", "audio/vorbis" to "Vorbis",
        "audio/flac" to "FLAC", "audio/vnd.dts" to "DTS", "audio/true-hd" to "TrueHD",
        "application/x-subrip" to "SRT", "text/vtt" to "WebVTT", "text/x-ssa" to "SSA",
        "application/pgs" to "PGS (picture)",
    )
}
