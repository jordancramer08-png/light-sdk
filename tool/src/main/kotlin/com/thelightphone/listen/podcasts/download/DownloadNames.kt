package com.thelightphone.listen.podcasts.download

import com.thelightphone.listen.podcasts.feed.TranscriptLink
import com.thelightphone.listen.podcasts.transcripts.TranscriptFormat
import com.thelightphone.listen.podcasts.transcripts.Transcripts

/** Audio file endings Listen's player reads (the same formats as music). */
private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "mp4", "ogg", "oga", "opus", "flac", "wav")

/**
 * The file ending for a downloaded episode: from the address when it names a known audio
 * format ("…/ep.m4a?token=1" → "m4a"), else from the type ("audio/mpeg" → "mp3"), else "mp3"
 * (by far the most common; the player reads the file's contents, not its name, anyway).
 */
fun audioExtension(type: String?, url: String): String {
    val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
    val fromUrl = path.substringAfterLast('.', "").lowercase()
    if (fromUrl in AUDIO_EXTENSIONS) return fromUrl
    return when (type?.lowercase()?.substringBefore(';')?.trim()) {
        "audio/mp4", "audio/x-m4a", "audio/m4a", "video/mp4" -> "m4a"
        "audio/x-m4b" -> "m4b"
        "audio/aac", "audio/aacp" -> "aac"
        "audio/ogg", "application/ogg" -> "ogg"
        "audio/opus" -> "opus"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        else -> "mp3"
    }
}

/** The transcript to download: the best format Listen reads (timed first, PODCASTS.md feature 10). */
fun pickTranscript(links: List<TranscriptLink>): Pair<TranscriptLink, TranscriptFormat>? =
    links.mapNotNull { link -> Transcripts.formatOf(link.type, link.url)?.let { link to it } }
        .minByOrNull { it.second.ordinal }

/** The ending a transcript is saved with. */
fun transcriptExtension(format: TranscriptFormat): String = when (format) {
    TranscriptFormat.VTT -> "vtt"
    TranscriptFormat.SRT -> "srt"
    TranscriptFormat.JSON -> "json"
    TranscriptFormat.HTML -> "html"
    TranscriptFormat.TEXT -> "txt"
}

/** "Downloading… 42% · 23 of 54 MB", "Waiting to download…", or "Paused at 42%…" while Android has it on hold. */
fun downloadProgressText(a: DownloadStatus.Active): String {
    val pct = a.percent
    return when {
        a.waiting && a.bytes > 0 -> "Paused at ${pct?.let { "$it%" } ?: sizeText(a.bytes)}. It carries on by itself."
        a.waiting -> "Waiting to download…"
        pct != null && a.total != null -> "Downloading… $pct% · ${sizeText(a.bytes)} of ${sizeText(a.total)}"
        else -> "Downloading… ${sizeText(a.bytes)}"
    }
}

/** The short form for list rows: "Downloading 42%", "Waiting to download", "Download failed". */
fun downloadRowText(status: DownloadStatus?): String = when (status) {
    null -> ""
    is DownloadStatus.Failed -> "Download failed"
    is DownloadStatus.Active -> when {
        status.waiting && status.bytes == 0L -> "Waiting to download"
        status.waiting -> "Download paused"
        else -> "Downloading ${status.percent?.let { "$it%" } ?: sizeText(status.bytes)}"
    }
}

/** "820 KB", "54 MB", "1.2 GB". */
fun sizeText(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> "%.1f GB".format(bytes / gb)
        bytes >= mb -> "%.0f MB".format(bytes / mb)
        else -> "%.0f KB".format(maxOf(bytes, 0) / kb)
    }
}
