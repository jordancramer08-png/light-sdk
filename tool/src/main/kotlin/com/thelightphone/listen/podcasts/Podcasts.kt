package com.thelightphone.listen.podcasts

import android.util.Log
import com.thelightphone.listen.artwork.ArtSource
import com.thelightphone.listen.artwork.ArtworkCache
import com.thelightphone.listen.books.ChapterMark
import com.thelightphone.listen.playback.EpisodeToPlay
import com.thelightphone.listen.playback.PlaybackHub
import com.thelightphone.listen.podcasts.chapters.ChapterList
import com.thelightphone.listen.podcasts.chapters.pscChapters
import com.thelightphone.listen.podcasts.download.pickTranscript
import com.thelightphone.listen.podcasts.transcripts.MAX_TRANSCRIPT_BYTES
import com.thelightphone.listen.podcasts.transcripts.Transcripts
import com.thelightphone.listen.podcasts.feed.Episode
import com.thelightphone.listen.podcasts.feed.NotAFeedException
import com.thelightphone.listen.podcasts.net.ITunesSearch
import com.thelightphone.listen.podcasts.net.NetError
import com.thelightphone.listen.podcasts.net.PodcastFetcher
import com.thelightphone.listen.podcasts.net.SearchResult
import com.thelightphone.listen.podcasts.store.DownloadedFiles
import com.thelightphone.listen.podcasts.store.EpisodeSort
import com.thelightphone.listen.podcasts.store.EpisodeState
import com.thelightphone.listen.podcasts.store.EpisodeStateStore
import com.thelightphone.listen.podcasts.store.FeedNotes
import com.thelightphone.listen.podcasts.store.FeedSnapshot
import com.thelightphone.listen.podcasts.store.NEW_EPISODES_WINDOW_DAYS
import com.thelightphone.listen.podcasts.store.NewEpisode
import com.thelightphone.listen.podcasts.store.ShowFiles
import com.thelightphone.listen.podcasts.store.Subscription
import com.thelightphone.listen.podcasts.store.SubscriptionStore
import com.thelightphone.listen.podcasts.store.episodeKey
import com.thelightphone.listen.podcasts.store.newEpisodes
import com.thelightphone.listen.storage.ListenPaths
import com.thelightphone.sdk.LightNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** What the Podcasts screens say under their lists: work going on, or how the last job went. */
data class PodcastStatus(val text: String, val working: Boolean)

/** One downloaded episode, for the Downloads screen. */
data class DownloadedEpisode(
    val showId: String,
    val episodeId: String,
    val title: String,
    val showTitle: String,
    val bytes: Long,
    val played: Boolean,
    val downloadedAt: Long,
)

/** An episode made ready to play, or why it couldn't be. */
sealed interface EpisodeReady {
    data class Ready(val episode: EpisodeToPlay) : EpisodeReady
    data class Failed(val message: String) : EpisodeReady
}

/** What a search on Apple's directory found, or why it couldn't. */
sealed interface SearchOutcome {
    data class Found(val results: List<SearchResult>) : SearchOutcome
    data class Failed(val message: String) : SearchOutcome
}

/**
 * The app-wide podcasts: the follow list (/sdcard/Listen/.state/podcasts.json), each
 * episode's played mark and downloads (podcast_episodes.json), the recent episodes behind New
 * Episodes, and the jobs that change them (add, refresh, unfollow). Every screen calls
 * [load] when it shows: the first time it reads the files; after that it only re-reads the
 * list if something else changed it (a restore from the PC), and always folds in the PC's
 * inbox if Podcasts.cmd left one.
 *
 * File work runs on [scope], one job at a time; feeds are fetched on the IO threads, two at a
 * time (a big feed takes a lot of memory while it's read, and the phone has about 128 MB).
 */
object Podcasts {
    private const val TAG = "Listen"

    /** Channel art is kept at this size (shorter side, px): sharp on a show page, small on disk. */
    private const val ART_PX = 600
    private const val ART_FILE = "art.jpg"
    private const val PARALLEL_FEEDS = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val feedFetching = Dispatchers.IO.limitedParallelism(PARALLEL_FEEDS)
    private val fetcher by lazy { PodcastFetcher() }
    private val search by lazy { ITunesSearch() }

    private var store: SubscriptionStore? = null
    private var episodeStore: EpisodeStateStore? = null
    /** podcasts.json's change time when Listen last read or wrote it (only used on [scope]). */
    private var knownModified = -1L

    private val _shows = MutableStateFlow<List<Subscription>>(emptyList())
    /** Followed shows, A–Z. */
    val shows: StateFlow<List<Subscription>> = _shows.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _states = MutableStateFlow<Map<String, EpisodeState>>(emptyMap())
    /** Every touched episode's state, keyed "showId/episodeId" ([episodeKey]). */
    val states: StateFlow<Map<String, EpisodeState>> = _states.asStateFlow()

    /** Each followed show's episodes from the last [NEW_EPISODES_WINDOW_DAYS] days (all New Episodes needs). */
    private val recent = MutableStateFlow<Map<String, List<Episode>>>(emptyMap())

    /** Unplayed, recent episodes from every followed show, newest first. */
    val newEpisodes: StateFlow<List<NewEpisode>> = combine(_shows, recent, _states) { shows, eps, states ->
        newEpisodes(shows, eps, states, System.currentTimeMillis())
    }.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, emptyList())

    private val _status = MutableStateFlow<PodcastStatus?>(null)
    val status: StateFlow<PodcastStatus?> = _status.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _feedErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Shows whose last refresh failed, with why ("This show only offers an insecure link…"). */
    val feedErrors: StateFlow<Map<String, String>> = _feedErrors.asStateFlow()

    fun load() {
        scope.launch {
            try {
                val firstTime = store == null
                val s = openStore()
                if (ListenPaths.podcastSubscriptions.lastModified() != knownModified) s.reload()
                val fromPc = s.mergeInbox()
                publish(s)
                if (firstTime) loadRecent(s.shows.value)
                if (fromPc) fetchNewShows(s)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read podcasts", e)
            }
        }
    }

    /**
     * After a sync from Podcasts.cmd: shows followed on the PC have no episodes on the phone
     * yet, so their feeds are fetched now (only theirs; nothing is downloaded).
     */
    private suspend fun fetchNewShows(s: SubscriptionStore) {
        val missing = s.shows.value.filter { showFiles(it.showId).loadSnapshot() == null }
        if (missing.isEmpty()) return
        _status.value = PodcastStatus("Getting episodes for ${showCount(missing.size)} from the PC…", working = true)
        val failed = coroutineScope {
            missing.map { sub -> async(feedFetching) { refreshOne(s, sub) } }.awaitAll()
        }.count { !it }
        publish(s)
        _status.value = PodcastStatus(
            if (failed == 0) "Added ${showCount(missing.size)} from the PC." else "$failed of ${missing.size} new shows couldn't be checked. Open the show to see why.",
            working = false,
        )
    }

    /** Clears the last job's message (when a Podcasts screen is opened again). */
    fun clearStatus() {
        if (_status.value?.working == false) _status.value = null
    }

    // ---- Adding and following ----

    /**
     * Follows the show at the [typed] address: fetches its feed first (so a wrong address or
     * a page that isn't a feed is caught before it's added), saves the feed and its art, then
     * adds it to the list. Progress and the outcome show in [status].
     */
    fun add(typed: String) {
        val url = PodcastIds.cleanTypedFeedAddress(typed)
        if (url == null) {
            _status.value = PodcastStatus("That doesn't look like a web address.", working = false)
            return
        }
        _status.value = PodcastStatus("Adding show…", working = true)
        scope.launch {
            _status.value = try {
                val s = openStore()
                val existing = s.findByUrl(url)
                if (existing != null && existing.following) {
                    PodcastStatus("You already follow ${existing.title.ifEmpty { "that show" }}.", working = false)
                } else {
                    PodcastStatus("Added ${follow(s, url)}.", working = false)
                }
            } catch (e: Exception) {
                PodcastStatus(failure(e, url), working = false)
            }
        }
    }

    /** Fetches, saves and follows. Returns the show's title. Runs on [scope]. */
    private suspend fun follow(s: SubscriptionStore, url: String): String {
        val fetched = withContext(feedFetching) { fetcher.fetchFeed(url) }
        val show = fetched.feed.show
        val sub = s.follow(fetched.movedTo ?: url, show.title, show.author, show.artUrl)
        val files = showFiles(sub.showId)
        files.save(fetched.feed, System.currentTimeMillis())
        show.artUrl?.let { saveArt(it, files.file(ART_FILE)) }
        recent.update { it + (sub.showId to recentOf(fetched.feed.episodes)) }
        publish(s)
        return show.title
    }

    /**
     * Unfollows [showId]. With [deleteDownloads], its downloaded episodes are deleted too;
     * once nothing downloaded is left, the show's folder (feed copy and art) goes as well.
     */
    fun unfollow(showId: String, deleteDownloads: Boolean) {
        scope.launch {
            try {
                val s = openStore()
                val title = s.get(showId)?.title.orEmpty()
                s.unfollow(showId)
                val e = openEpisodeStore()
                val files = showFiles(showId)
                if (deleteDownloads) {
                    for ((episodeId, download) in e.downloadsOf(showId)) {
                        download.allFiles.forEach { files.file(it).delete() }
                        e.clearDownload(showId, episodeId)
                    }
                }
                if (e.downloadsOf(showId).isEmpty()) files.dir.deleteRecursively()
                recent.update { it - showId }
                _feedErrors.update { it - showId }
                publish(s)
                _status.value = PodcastStatus("Unfollowed ${title.ifEmpty { "the show" }}.", working = false)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't unfollow $showId", e)
            }
        }
    }

    /** How many downloaded episodes [showId] has (to ask about them before unfollowing). */
    fun downloadCount(showId: String): Int = _states.value.count { (k, v) -> k.startsWith("$showId/") && v.download != null }

    // ---- Refresh ----

    /** Fetches every followed show's feed again (two at a time), then updates New Episodes. */
    fun refreshAll() {
        if (_refreshing.value) return
        _refreshing.value = true
        scope.launch {
            try {
                val s = openStore()
                val shows = s.shows.value
                val done = AtomicInteger(0)
                _status.value = PodcastStatus("Checking ${showCount(shows.size)}…", working = true)
                val failed = coroutineScope {
                    shows.map { sub ->
                        async(feedFetching) {
                            val ok = refreshOne(s, sub)
                            _status.value = PodcastStatus("Checked ${done.incrementAndGet()} of ${shows.size}…", working = true)
                            ok
                        }
                    }.awaitAll()
                }.count { !it }
                publish(s)
                val newCount = newEpisodes.value.size
                _status.value = PodcastStatus(
                    when {
                        failed > 0 -> "$failed of ${shows.size} couldn't be checked. Open the show to see why."
                        newCount == 0 -> "Up to date. No new episodes."
                        newCount == 1 -> "Up to date. 1 new episode."
                        else -> "Up to date. $newCount new episodes."
                    },
                    working = false,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Refresh failed", e)
                _status.value = PodcastStatus("Couldn't refresh.", working = false)
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** One show's refresh. Returns false (and remembers why) when it couldn't be fetched. */
    private fun refreshOne(s: SubscriptionStore, sub: Subscription): Boolean =
        try {
            val fetched = fetcher.fetchFeed(sub.feedUrl)
            val show = fetched.feed.show
            val files = showFiles(sub.showId)
            files.save(fetched.feed, System.currentTimeMillis())
            val art = files.file(ART_FILE)
            if (show.artUrl != null && (!art.isFile || show.artUrl != sub.artUrl)) saveArt(show.artUrl, art)
            s.updateFromFeed(sub.showId, show.title, show.author, show.artUrl, fetched.movedTo)
            recent.update { it + (sub.showId to recentOf(fetched.feed.episodes)) }
            _feedErrors.update { it - sub.showId }
            true
        } catch (e: Exception) {
            _feedErrors.update { it + (sub.showId to failure(e, sub.feedUrl)) }
            false
        }

    // ---- One show ----

    /** A show's saved episode list (feed.json), or null before its first fetch. Call off the main thread. */
    suspend fun snapshot(showId: String): FeedSnapshot? = withContext(Dispatchers.IO) { showFiles(showId).loadSnapshot() }

    /** A show's saved descriptions. Call off the main thread. */
    suspend fun notes(showId: String): FeedNotes = withContext(Dispatchers.IO) { showFiles(showId).loadNotes() }

    fun setSort(showId: String, sort: EpisodeSort) {
        scope.launch {
            val s = openStore()
            s.setSort(showId, sort)
            publish(s)
        }
    }

    // ---- Played marks ----

    fun state(showId: String, episodeId: String): EpisodeState = _states.value[episodeKey(showId, episodeId)] ?: EpisodeState()

    fun markPlayed(showId: String, episodeId: String) = changeStates { it.markPlayed(showId, episodeId) }

    fun markUnplayed(showId: String, episodeId: String) = changeStates { it.markUnplayed(showId, episodeId) }

    /** "Mark all played" on New Episodes: every episode in it right now. */
    fun markAllNewPlayed() {
        val keys = newEpisodes.value.map { it.show.showId to it.episode.id }
        changeStates { it.markAllPlayed(keys) }
    }

    private fun changeStates(change: (EpisodeStateStore) -> Unit) {
        scope.launch {
            try {
                change(openEpisodeStore())
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't save podcast_episodes.json", e)
            }
        }
    }

    // ---- Downloads ----

    /** Saves that an episode's files are on the phone (called by the download job, on its thread). */
    fun recordDownload(showId: String, episodeId: String, files: DownloadedFiles) =
        openEpisodeStore().setDownloaded(showId, episodeId, files)

    /** Remove Download: deletes the audio, chapters and transcript; the episode stays listed. */
    fun removeDownload(showId: String, episodeId: String) {
        scope.launch {
            try {
                val e = openEpisodeStore()
                val files = showFiles(showId)
                e.get(showId, episodeId).download?.allFiles?.forEach { files.file(it).delete() }
                deleteParts(files, episodeId)
                e.clearDownload(showId, episodeId)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't remove download $showId/$episodeId", e)
            }
        }
    }

    /**
     * Every downloaded episode across all shows, newest download first, with the titles
     * from each show's saved feed. Off the main thread.
     */
    suspend fun downloadedEpisodes(states: Map<String, EpisodeState>): List<DownloadedEpisode> = withContext(Dispatchers.IO) {
        val byShow = states.entries
            .mapNotNull { (key, state) -> state.download?.let { key to state } }
            .groupBy { it.first.substringBefore('/') }
        byShow.flatMap { (showId, entries) ->
            val snapshot = showFiles(showId).loadSnapshot()
            val titles = snapshot?.episodes?.associate { it.id to it.title }.orEmpty()
            val showTitle = _shows.value.firstOrNull { it.showId == showId }?.title?.ifEmpty { null } ?: snapshot?.show?.title.orEmpty()
            entries.map { (key, state) ->
                val episodeId = key.substringAfter('/')
                DownloadedEpisode(
                    showId = showId,
                    episodeId = episodeId,
                    title = titles[episodeId] ?: "Episode",
                    showTitle = showTitle,
                    bytes = state.download?.bytes ?: 0,
                    played = state.played,
                    downloadedAt = state.download?.downloadedAt ?: 0,
                )
            }
        }.sortedByDescending { it.downloadedAt }
    }

    /** Removes every downloaded episode that's been played (audio, chapters and transcript); the episodes stay listed. */
    fun removeAllPlayedDownloads() {
        scope.launch {
            try {
                val e = openEpisodeStore()
                for ((key, state) in e.states.value) {
                    val download = state.download ?: continue
                    if (!state.played) continue
                    val showId = key.substringBefore('/')
                    val episodeId = key.substringAfter('/')
                    val files = showFiles(showId)
                    download.allFiles.forEach { files.file(it).delete() }
                    deleteParts(files, episodeId)
                    e.clearDownload(showId, episodeId)
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Couldn't remove played downloads", ex)
            }
        }
    }

    /** Deletes what a cancelled download had saved so far. */
    fun deletePartialDownload(showId: String, episodeId: String) {
        scope.launch { deleteParts(showFiles(showId), episodeId) }
    }

    private fun deleteParts(files: ShowFiles, episodeId: String) {
        files.dir.listFiles { f -> f.name.startsWith("$episodeId.") && f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    /** Everything podcasts use on the phone (downloads, feed copies, art), in bytes. Off the main thread. */
    suspend fun storageBytes(): Long = withContext(Dispatchers.IO) {
        ListenPaths.podcasts.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    // ---- Playback ----

    /**
     * An episode ready for the player. Downloaded: its file, saved chapters and transcript.
     * Not downloaded (and [allowStream]): streamed from the address its tracking redirects
     * lead to (each hop upgraded to https), with the feed's chapters; its transcript is read
     * online. Off the main thread.
     */
    suspend fun prepareEpisode(showId: String, episodeId: String, allowStream: Boolean): EpisodeReady = withContext(Dispatchers.IO) {
        val state = openEpisodeStore().get(showId, episodeId)
        val files = showFiles(showId)
        val snapshot = files.loadSnapshot()
        val episode = snapshot?.episodes?.firstOrNull { it.id == episodeId }
        val showTitle = _shows.value.firstOrNull { it.showId == showId }?.title?.ifEmpty { null } ?: snapshot?.show?.title.orEmpty()
        val durationMs = state.durationMs.takeIf { it > 0 } ?: episode?.durationMs ?: 0
        val download = state.download
        val audio = download?.let { files.file(it.audio) }?.takeIf { it.isFile }
        if (download != null && audio != null) {
            val transcript = download.transcript?.let { files.file(it) }?.takeIf { it.isFile }
            return@withContext EpisodeReady.Ready(
                EpisodeToPlay(
                    showId = showId,
                    episodeId = episodeId,
                    title = episode?.title ?: "Episode",
                    showTitle = showTitle,
                    audio = audio,
                    durationMs = durationMs,
                    chapters = download.chapters?.let { readChapters(files.file(it)) }.orEmpty(),
                    positionMs = state.positionMs,
                    lastPlayedAt = state.lastPlayedAt,
                    played = state.played,
                    transcript = transcript,
                    transcriptType = download.transcriptType,
                    hasTranscript = transcript != null || episode?.hasTranscript == true,
                ),
            )
        }
        if (!allowStream) return@withContext EpisodeReady.Failed("This episode isn't downloaded.")
        if (episode == null) return@withContext EpisodeReady.Failed("This episode isn't in the feed any more. Refresh the show and try again.")
        val streamUrl = try {
            fetcher.resolveStreamUrl(episode.enclosureUrl)
        } catch (e: NetError.NoConnection) {
            Log.w(TAG, "Stream: couldn't connect to ${episode.enclosureUrl}", e)
            return@withContext EpisodeReady.Failed(noConnectionText(streaming = true))
        } catch (e: Exception) {
            return@withContext EpisodeReady.Failed(failure(e, episode.enclosureUrl))
        }
        EpisodeReady.Ready(
            EpisodeToPlay(
                showId = showId,
                episodeId = episodeId,
                title = episode.title,
                showTitle = showTitle,
                audio = files.file("$episodeId.stream"),
                durationMs = durationMs,
                chapters = streamChapters(episode),
                positionMs = state.positionMs,
                lastPlayedAt = state.lastPlayedAt,
                played = state.played,
                transcript = null,
                transcriptType = null,
                streamUrl = streamUrl,
                hasTranscript = episode.hasTranscript,
            ),
        )
    }

    /** For restoring the player: the episode, streamed if need be; null when it can't be had right now. */
    suspend fun episodeToPlay(showId: String, episodeId: String): EpisodeToPlay? =
        (prepareEpisode(showId, episodeId, allowStream = true) as? EpisodeReady.Ready)?.episode

    /** A streamed episode's chapters: the feed's chapters file, else the feed's own list (best effort). */
    private fun streamChapters(episode: Episode): List<ChapterMark> {
        val list = try {
            episode.chaptersUrl?.let { fetcher.fetchChapters(it) }
        } catch (e: Exception) {
            null
        } ?: pscChapters(episode.pscChapters)
        return list?.chapters?.map { ChapterMark(it.title, it.startMs) }.orEmpty()
    }

    private val chaptersJson = Json { ignoreUnknownKeys = true }

    private fun readChapters(file: File): List<ChapterMark> = try {
        chaptersJson.decodeFromString(ChapterList.serializer(), file.readText()).chapters.map { ChapterMark(it.title, it.startMs) }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Plays an episode (its download, or streamed over Wi-Fi or mobile data), then calls
     * [onStarted] on the main thread (to open Now Playing). Problems show in [status].
     */
    fun play(showId: String, episodeId: String, onStarted: () -> Unit) {
        val streaming = state(showId, episodeId).download == null
        if (streaming) _status.value = PodcastStatus("Starting the stream…", working = true)
        mainScope.launch {
            when (val ready = prepareEpisode(showId, episodeId, allowStream = true)) {
                is EpisodeReady.Failed -> _status.value = PodcastStatus(ready.message, working = false)
                is EpisodeReady.Ready -> {
                    if (_status.value?.working == true) _status.value = null
                    PlaybackHub.playEpisode(ready.episode)
                    onStarted()
                }
            }
        }
    }

    /**
     * Saves where an episode is (from the player). Reaching its end marks it played, and then
     * [onFinished] runs (for "After finishing: Delete download").
     */
    fun savePlayback(showId: String, episodeId: String, positionMs: Long, durationMs: Long, touch: Boolean, onFinished: () -> Unit) {
        scope.launch {
            try {
                if (openEpisodeStore().savePosition(showId, episodeId, positionMs, durationMs, touch)) onFinished()
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't save the episode's place", e)
            }
        }
    }

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * An episode's transcript, read into lines: from its download when it has one, otherwise
     * fetched from the feed's address (and not saved). Off the main thread.
     */
    suspend fun loadTranscript(showId: String, episodeId: String): TranscriptLoad = withContext(Dispatchers.IO) {
        try {
            val files = showFiles(showId)
            val download = openEpisodeStore().get(showId, episodeId).download
            val saved = download?.transcript?.let { files.file(it) }?.takeIf { it.isFile }
            val transcript = if (saved != null) {
                if (saved.length() > MAX_TRANSCRIPT_BYTES) return@withContext TranscriptLoad.Failed("This transcript is too long to show.")
                val format = Transcripts.formatOf(download.transcriptType, saved.name)
                    ?: return@withContext TranscriptLoad.Failed("Listen can't read this kind of transcript.")
                Transcripts.parse(format, saved.readText())
            } else {
                val episode = files.loadSnapshot()?.episodes?.firstOrNull { it.id == episodeId }
                val (link, _) = pickTranscript(episode?.transcripts.orEmpty())
                    ?: return@withContext TranscriptLoad.Failed("This episode has no transcript Listen can read.")
                fetcher.fetchTranscript(link.url, link.type)
            }
            if (transcript == null) TranscriptLoad.Failed("The transcript is empty.") else TranscriptLoad.Loaded(transcript)
        } catch (e: Exception) {
            TranscriptLoad.Failed(failure(e, "transcript"))
        }
    }

    // ---- Search ----

    /** Searches Apple's directory. Blocks no one else's work: runs on its own IO thread. */
    suspend fun search(term: String): SearchOutcome = withContext(Dispatchers.IO) {
        try {
            SearchOutcome.Found(search.search(term))
        } catch (e: Exception) {
            SearchOutcome.Failed(failure(e, "itunes.apple.com"))
        }
    }

    /** True when [feedUrl] (however it's written) is a show Jordan follows. */
    fun isFollowed(feedUrl: String, shows: List<Subscription>): Boolean {
        val n = PodcastIds.normalizeFeedUrl(feedUrl)
        return shows.any { n in it.knownUrls }
    }

    fun followedShow(feedUrl: String, shows: List<Subscription>): Subscription? {
        val n = PodcastIds.normalizeFeedUrl(feedUrl)
        return shows.firstOrNull { n in it.knownUrls }
    }

    // ---- Connection ----

    @Volatile
    private var network: LightNetwork? = null

    /** Lets Listen ask Android whether the phone is online (set by every screen and the download job). */
    fun useNetworkCheck(check: LightNetwork) {
        network = check
    }

    /**
     * What to say when a connection couldn't be made (even after a retry): "No connection"
     * only when Android says the phone has no working internet at all, over Wi-Fi or mobile
     * data. Otherwise the phone is online and it's the show's server that can't be reached.
     */
    fun noConnectionText(streaming: Boolean): String = when {
        network?.isOnline != false -> "Couldn't reach the show's server. Try again in a moment."
        streaming -> "No connection. Download the episode to listen offline."
        else -> "No connection. Check Wi-Fi or mobile data."
    }

    // ---- Files ----

    private fun saveArt(url: String, file: File) {
        try {
            if (!ArtworkCache.saveScaled(fetcher.fetchArt(url), ART_PX, file)) Log.w(TAG, "Show art at $url isn't a picture")
        } catch (e: Exception) {
            // No art is fine: rows show the title's first letter instead.
            Log.w(TAG, "Couldn't get show art $url: $e")
        }
    }

    /** Reads every followed show's saved list once, keeping only recent episodes in memory. */
    private fun loadRecent(shows: List<Subscription>) {
        val map = HashMap<String, List<Episode>>()
        for (sub in shows) {
            showFiles(sub.showId).loadSnapshot()?.let { map[sub.showId] = recentOf(it.episodes) }
        }
        recent.value = map
    }

    private fun recentOf(episodes: List<Episode>): List<Episode> {
        val since = System.currentTimeMillis() - (NEW_EPISODES_WINDOW_DAYS + 1) * 24L * 60 * 60 * 1000
        return episodes.filter { (it.publishedAt ?: 0) >= since }
    }

    private fun failure(e: Exception, url: String): String = when (e) {
        is NetError -> {
            Log.w(TAG, "Network problem with $url", e)
            if (e is NetError.NoConnection) noConnectionText(streaming = false) else e.message ?: "Couldn't load that address."
        }
        is NotAFeedException -> "That address isn't a podcast feed."
        is IOException -> {
            Log.w(TAG, "Couldn't load $url: $e")
            "Couldn't load that address."
        }
        else -> {
            Log.w(TAG, "Couldn't load $url", e)
            "Something went wrong."
        }
    }

    private fun openStore(): SubscriptionStore =
        store ?: SubscriptionStore(ListenPaths.podcastSubscriptions, ListenPaths.podcastInbox).also {
            store = it
            makePodcastsFolder()
            openEpisodeStore()
        }

    @Synchronized
    private fun openEpisodeStore(): EpisodeStateStore =
        episodeStore ?: EpisodeStateStore(ListenPaths.podcastEpisodes).also { e ->
            episodeStore = e
            // Mirror its states for the screens (it updates on whichever thread saved).
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { e.states.collect { _states.value = it } }
        }

    /** Podcasts/ with a .nomedia file, so other apps don't list downloaded episodes as music. */
    private fun makePodcastsFolder() {
        try {
            val dir = ListenPaths.podcasts
            if (dir.isDirectory || dir.mkdirs()) File(dir, ".nomedia").let { if (!it.exists()) it.createNewFile() }
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't make the Podcasts folder: $e")
        }
    }

    private fun publish(s: SubscriptionStore) {
        knownModified = ListenPaths.podcastSubscriptions.lastModified()
        _shows.value = s.shows.value
        _loaded.value = true
    }

    fun showFiles(showId: String) = ShowFiles(File(ListenPaths.podcasts, showId))

    /** A show's saved channel art (art.jpg in its folder), for [com.thelightphone.listen.artwork.ArtImage]. */
    fun artSource(showId: String): ArtSource {
        val dir = File(ListenPaths.podcasts, showId)
        return ArtSource(
            key = "show:$showId|${File(dir, ART_FILE).lastModified()}",
            audioFile = null,
            folder = dir,
            folderImages = listOf(ART_FILE),
            folderFirst = true,
        )
    }
}

/** "1 show", "12 shows". */
fun showCount(count: Int): String = if (count == 1) "1 show" else "%,d shows".format(count)
