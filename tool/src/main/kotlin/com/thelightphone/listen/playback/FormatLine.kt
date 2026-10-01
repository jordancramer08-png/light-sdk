package com.thelightphone.listen.playback

import com.thelightphone.sdk.audio.LightAudioFormat
import java.util.Locale

/**
 * The small quality line on Now Playing: "MP3 · 190 kbps · 44.1 kHz".
 *
 * What the player read from the file comes first. When the file doesn't state a bitrate
 * (FLAC, WAV, some MP3s) it is worked out from the file's size and length. Null when there
 * is nothing to show yet.
 */
fun formatLine(format: LightAudioFormat?, fileName: String, fileBytes: Long, durationMs: Long): String? {
    val codec = codecName(format, fileName)
    val bitrate = format?.bitrate?.takeIf { it > 0 }?.toLong()
        ?: if (fileBytes > 0 && durationMs > 0) fileBytes * 8 * 1000 / durationMs else null
    val parts = listOfNotNull(
        codec,
        bitrate?.let { "${(it + 500) / 1000} kbps" },
        format?.sampleRate?.takeIf { it > 0 }?.let(::sampleRateText),
    )
    // Only a guess from the file name: wait for the player to say what it's playing.
    if (format == null && parts.size < 2) return null
    return parts.joinToString(" · ").ifEmpty { null }
}

/** The codec's everyday name; the file's extension when the player hasn't said. */
private fun codecName(format: LightAudioFormat?, fileName: String): String? {
    val mime = format?.mimeType
    val known = when (mime) {
        "audio/mpeg" -> "MP3"
        "audio/mp4a-latm" -> "AAC"
        "audio/flac" -> "FLAC"
        "audio/vorbis" -> "Ogg Vorbis"
        "audio/opus" -> "Opus"
        "audio/alac" -> "ALAC"
        "audio/raw" -> if (format?.containerMimeType?.contains("wav") == true) "WAV" else "PCM"
        null -> null
        else -> mime.substringAfter('/').uppercase(Locale.US)
    }
    return known ?: fileName.substringAfterLast('.', "").uppercase(Locale.US).ifEmpty { null }
}

/** 44100 → "44.1 kHz", 48000 → "48 kHz". */
private fun sampleRateText(hz: Int): String {
    val khz = String.format(Locale.US, "%.1f", hz / 1000.0).removeSuffix(".0")
    return "$khz kHz"
}
