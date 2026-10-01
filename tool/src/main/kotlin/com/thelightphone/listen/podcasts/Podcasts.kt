package com.thelightphone.listen.podcasts

import android.util.Log
import com.thelightphone.listen.artwork.ArtSource
import com.thelightphone.listen.artwork.ArtworkCache
import com.thelightphone.listen.podcasts.feed.NotAFeedException
import com.thelightphone.listen.podcasts.net.NetError
import com.thelightphone.listen.podcasts.net.PodcastFetcher
import com.thelightphone.listen.podcasts.store.ShowFiles
import com.thelightphone.listen.podcasts.store.Subscription
import com.thelightphone.listen.podcasts.store.SubscriptionStore
import com.thelightphone.listen.storage.ListenPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** What the Podcasts screen says under its list: work going on, or how the last add went. */
data class PodcastStatus(val text: String, val working: Boolean)

/**
 * The app-wide podcasts: the follow list (in /sdcard/Listen/.state/podcasts.json) and adding
 * shows. Every screen calls [load] when it shows: the first time it reads the list; after
 * that it only re-reads it if something else changed the file (a restore from the PC), and
 * it always folds in the PC's inbox if Podcasts.cmd left one. All file and network work
 * happens on [scope], off the main thread, one job at a time.
 */
object Podcasts {
    private const val TAG = "Listen"

    /** Channel art is kept at this size (shorter side, px): sharp on Now Playing, small on disk. */
    private const val ART_PX = 600

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val fetcher by lazy { PodcastFetcher() }

    private var store: SubscriptionStore? = null
    /** podcasts.json's change time when Listen last read or wrote it (only used on [scope]). */
    private var knownModified = -1L

    private val _shows = MutableStateFlow<List<Subscription>>(emptyList())
    /** Followed shows, A–Z. */
    val shows: StateFlow<List<Subscription>> = _shows.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _status = MutableStateFlow<PodcastStatus?>(null)
    val status: StateFlow<PodcastStatus?> = _status.asStateFlow()

    fun load() {
        scope.launch {
            try {
                val s = openStore()
                if (ListenPaths.podcastSubscriptions.lastModified() != knownModified) s.reload()
                s.mergeInbox()
                publish(s)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read the podcast list", e)
            }
        }
    }

    /** Clears the last add's message (when the Podcasts screen is opened again). */
    fun clearStatus() {
        if (_status.value?.working == false) _status.value = null
    }

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
                    val title = follow(s, url)
                    PodcastStatus("Added $title.", working = false)
                }
            } catch (e: NetError) {
                PodcastStatus(e.message ?: "Couldn't load that address.", working = false)
            } catch (e: NotAFeedException) {
                PodcastStatus("That address isn't a podcast feed.", working = false)
            } catch (e: IOException) {
                Log.w(TAG, "Couldn't add $url", e)
                PodcastStatus("Couldn't load that address.", working = false)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't add $url", e)
                PodcastStatus("Something went wrong adding that show.", working = false)
            }
        }
    }

    /** Fetches, saves and follows. Returns the show's title. Runs on [scope]. */
    private fun follow(s: SubscriptionStore, url: String): String {
        val fetched = fetcher.fetchFeed(url)
        val show = fetched.feed.show
        val sub = s.follow(fetched.movedTo ?: url, show.title, show.author, show.artUrl)
        val files = showFiles(sub.showId)
        files.save(fetched.feed, System.currentTimeMillis())
        show.artUrl?.let { saveArt(it, files.file(ART_FILE)) }
        publish(s)
        return show.title
    }

    private fun saveArt(url: String, file: File) {
        try {
            if (!ArtworkCache.saveScaled(fetcher.fetchArt(url), ART_PX, file)) Log.w(TAG, "Show art at $url isn't a picture")
        } catch (e: Exception) {
            // No art is fine: the row shows the title's first letter instead.
            Log.w(TAG, "Couldn't get show art $url: $e")
        }
    }

    private fun openStore(): SubscriptionStore =
        store ?: SubscriptionStore(ListenPaths.podcastSubscriptions, ListenPaths.podcastInbox).also {
            store = it
            makePodcastsFolder()
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

    private const val ART_FILE = "art.jpg"

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
