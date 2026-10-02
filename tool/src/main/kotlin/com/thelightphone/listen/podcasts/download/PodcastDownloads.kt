package com.thelightphone.listen.podcasts.download

import android.util.Log
import com.thelightphone.listen.podcasts.Podcasts
import com.thelightphone.listen.podcasts.chapters.ChapterList
import com.thelightphone.listen.podcasts.chapters.Id3Chapters
import com.thelightphone.listen.podcasts.chapters.pscChapters
import com.thelightphone.listen.podcasts.feed.Episode
import com.thelightphone.listen.podcasts.net.NetError
import com.thelightphone.listen.podcasts.net.OkHttpTransport
import com.thelightphone.listen.podcasts.net.PodcastFetcher
import com.thelightphone.listen.podcasts.store.DownloadedFiles
import com.thelightphone.listen.podcasts.store.ShowFiles
import com.thelightphone.listen.podcasts.store.episodeKey
import com.thelightphone.listen.storage.AtomicFile
import com.thelightphone.listen.storage.ListenPaths
import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Where a download is: going (or waiting to), or failed with a reason. Done downloads have no status. */
sealed interface DownloadStatus {
    /** [bytes] of [total] so far; [waiting] before it starts, or while Android has paused it. */
    data class Active(val bytes: Long, val total: Long?, val waiting: Boolean) : DownloadStatus {
        val percent: Int? get() = total?.takeIf { it > 0 }?.let { ((bytes * 100) / it).toInt().coerceIn(0, 100) }
    }

    data class Failed(val message: String) : DownloadStatus
}

/**
 * The background job behind one download. LightWork (Android's WorkManager) keeps it
 * running after Listen is left, and starts it again if Android stops it (downloads resume
 * where they stopped). Only ever started by a tap: Listen never downloads on its own.
 */
@LightJob("podcast-download")
val podcastDownload: LightJobHandler = { context, input ->
    Podcasts.useNetworkCheck(context.network)
    val showId = input["showId"]
    val episodeId = input["episodeId"]
    if (showId == null || episodeId == null) LightJobResult.Error() else PodcastDownloads.run(showId, episodeId)
}

/**
 * Episode downloads: start, cancel, retry, and the work itself. Each download is its own
 * LightWork job (tagged with its episode), so cancelling one never touches another. Audio
 * streams straight to /sdcard/Listen/Podcasts/<showId>/<episodeId>.<ext> in chunks; the
 * chapters and transcript come after it, then ID3 chapters are read from the file when the
 * feed has none.
 */
object PodcastDownloads {
    private const val JOB = "podcast-download"
    private const val TAG = "Listen"

    private val transport by lazy { OkHttpTransport() }
    private val fetcher by lazy { PodcastFetcher(transport) }
    private val chaptersJson = Json { encodeDefaults = false }

    private val _status = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())
    /** Downloads going on or failed, keyed "showId/episodeId". */
    val status: StateFlow<Map<String, DownloadStatus>> = _status.asStateFlow()

    /** Downloads Jordan cancelled, so their stopped jobs clean up instead of waiting to resume. */
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    private fun tagFor(key: String) = "$JOB:$key"

    /** Starts (or retries) downloading an episode. */
    fun start(context: SealedLightContext, showId: String, episodeId: String) {
        val key = episodeKey(showId, episodeId)
        cancelled.remove(key)
        _status.update { it + (key to DownloadStatus.Active(0, null, waiting = true)) }
        LightWork.enqueue(context, JOB, mapOf("showId" to showId, "episodeId" to episodeId), tag = tagFor(key))
    }

    /** Stops a download and throws away what it had. */
    fun cancel(context: SealedLightContext, showId: String, episodeId: String) {
        val key = episodeKey(showId, episodeId)
        cancelled.add(key)
        LightWork.cancel(context, tagFor(key))
        _status.update { it - key }
        Podcasts.deletePartialDownload(showId, episodeId)
    }

    /** Forgets a failed download's message (the episode shows Download again). */
    fun dismiss(showId: String, episodeId: String) = _status.update { it - episodeKey(showId, episodeId) }

    /** The job's work. Runs on an IO thread; returns how it went. */
    suspend fun run(showId: String, episodeId: String): LightJobResult = withContext(Dispatchers.IO) {
        val key = episodeKey(showId, episodeId)
        val job = coroutineContext[Job]
        try {
            val episode = Podcasts.snapshot(showId)?.episodes?.firstOrNull { it.id == episodeId }
                ?: return@withContext fail(key, "This episode isn't in the feed any more. Refresh the show and try again.")
            val files = Podcasts.showFiles(showId)
            val audioName = "$episodeId.${audioExtension(episode.enclosureType, episode.enclosureUrl)}"
            _status.update { it + (key to DownloadStatus.Active(0, episode.enclosureBytes, waiting = false)) }
            val bytes = ResumableDownload.download(
                transport = transport,
                url = episode.enclosureUrl,
                target = files.file(audioName),
                freeBytes = { ListenPaths.podcasts.usableSpace },
                onProgress = { have, total ->
                    _status.update { it + (key to DownloadStatus.Active(have, total ?: episode.enclosureBytes, waiting = false)) }
                },
                checkCancelled = { job?.ensureActive() },
            )
            job?.ensureActive()
            val chapters = saveChapters(episode, files, audioName)
            val transcript = saveTranscript(episode, files)
            Podcasts.recordDownload(
                showId,
                episodeId,
                DownloadedFiles(
                    audio = audioName,
                    bytes = bytes,
                    chapters = chapters,
                    transcript = transcript?.first,
                    transcriptType = transcript?.second,
                    downloadedAt = System.currentTimeMillis(),
                ),
            )
            _status.update { it - key }
            LightJobResult.Success()
        } catch (e: CancellationException) {
            if (key in cancelled) {
                Podcasts.deletePartialDownload(showId, episodeId)
                _status.update { it - key }
            } else {
                // Android stopped the job (its time limit, or the phone needs the memory). It
                // will run again and pick up where it stopped.
                _status.update { s ->
                    val active = s[key] as? DownloadStatus.Active
                    s + (key to (active?.copy(waiting = true) ?: DownloadStatus.Active(0, null, waiting = true)))
                }
            }
            throw e
        } catch (e: Exception) {
            fail(key, message(e))
        }
    }

    private fun fail(key: String, message: String): LightJobResult {
        _status.update { it + (key to DownloadStatus.Failed(message)) }
        return LightJobResult.Error(mapOf("message" to message))
    }

    private fun message(e: Exception): String = when (e) {
        is NetError, is NotEnoughSpace, is NotAudio -> {
            Log.w(TAG, "Download failed", e)
            if (e is NetError.NoConnection) Podcasts.noConnectionText(streaming = false) else e.message ?: "Couldn't download this episode."
        }
        is IOException -> {
            Log.w(TAG, "Download stopped: $e")
            "The download stopped. Check the connection, then tap Retry."
        }
        else -> {
            Log.w(TAG, "Download failed", e)
            "Couldn't download this episode."
        }
    }

    /**
     * Saves the episode's chapters as `<episodeId>.chapters.json`, from the best source:
     * the feed's chapters file, the feed's own chapter list, or (when neither gives any)
     * the ID3 chapter marks in the downloaded file. Returns the file name, or null without
     * chapters. A chapters problem never fails the download.
     */
    private fun saveChapters(episode: Episode, files: ShowFiles, audioName: String): String? {
        val list: ChapterList? = try {
            episode.chaptersUrl?.let { fetcher.fetchChapters(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't get chapters ${episode.chaptersUrl}: $e")
            null
        } ?: pscChapters(episode.pscChapters)
            ?: Id3Chapters.read(files.file(audioName))
        list ?: return null
        val name = "${episode.id}.chapters.json"
        return try {
            AtomicFile.writeText(files.file(name), chaptersJson.encodeToString(ChapterList.serializer(), list))
            name
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't save chapters: $e")
            null
        }
    }

    /** Saves the best transcript the feed offers, as downloaded. Returns (file name, type), or null. */
    private fun saveTranscript(episode: Episode, files: ShowFiles): Pair<String, String>? {
        val (link, format) = pickTranscript(episode.transcripts) ?: return null
        return try {
            val text = fetcher.fetchTranscriptText(link.url)
            val name = "${episode.id}.transcript.${transcriptExtension(format)}"
            AtomicFile.writeText(files.file(name), text)
            name to (link.type ?: format.name.lowercase())
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't get transcript ${link.url}: $e")
            null
        }
    }
}
