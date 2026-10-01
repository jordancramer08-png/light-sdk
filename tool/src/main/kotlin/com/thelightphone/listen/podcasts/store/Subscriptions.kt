package com.thelightphone.listen.podcasts.store

import com.thelightphone.listen.music.sortKey
import com.thelightphone.listen.podcasts.PodcastIds
import com.thelightphone.listen.storage.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** How a show's episodes are listed. */
@Serializable
enum class EpisodeSort { NEWEST, OLDEST }

/**
 * One show in the follow list. A show unfollowed is kept with [unfollowedAt] set (a
 * "tombstone"), so a merge with an older copy of the list (from the PC, or a backup) can't
 * bring it back, and following it again later is just a newer [followedAt].
 */
@Serializable
data class Subscription(
    /** Fixed when first followed ([PodcastIds.showIdFor]); never changes, even if the feed moves. */
    val showId: String,
    val feedUrl: String,
    /** Addresses the feed used before it moved, for matching. */
    val previousFeedUrls: List<String> = emptyList(),
    val title: String = "",
    val author: String = "",
    val artUrl: String? = null,
    /** When it was (last) followed, ms since 1970. New Episodes only shows episodes after this. */
    val followedAt: Long = 0,
    /** When it was unfollowed, or null while followed. */
    val unfollowedAt: Long? = null,
    val sort: EpisodeSort = EpisodeSort.NEWEST,
    /** When the details (title, author, art, address) last changed, for merging. */
    val updatedAt: Long = 0,
) {
    val following: Boolean get() = unfollowedAt == null || followedAt > unfollowedAt

    /** Every normalized address this show has been known by. */
    val knownUrls: Set<String> get() = (previousFeedUrls + feedUrl).map(PodcastIds::normalizeFeedUrl).toSet()
}

/** podcasts.json. */
@Serializable
data class SubscriptionsData(val version: Int = 1, val shows: List<Subscription> = emptyList())

/**
 * Combines two follow lists (phone ↔ PC, or the phone's list with an inbox), the same rule
 * on both sides (PODCAST_PLAN.md §8): shows match by id or by any known address; for each,
 * the later of followed/unfollowed wins; details come from whichever changed last. Nothing is
 * ever dropped, so merging never loses a show and merging twice changes nothing.
 */
fun mergeSubscriptions(mine: List<Subscription>, theirs: List<Subscription>): List<Subscription> {
    val result = mine.toMutableList()
    for (other in theirs) {
        val i = result.indexOfFirst { it.showId == other.showId || it.knownUrls.any { u -> u in other.knownUrls } }
        if (i < 0) {
            result += other
            continue
        }
        val a = result[i]
        val newer = if (other.updatedAt > a.updatedAt) other else a
        val followedAt = maxOf(a.followedAt, other.followedAt)
        val unfollowedAt = listOfNotNull(a.unfollowedAt, other.unfollowedAt).maxOrNull()
        val urls = (a.previousFeedUrls + other.previousFeedUrls + a.feedUrl + other.feedUrl)
            .distinctBy(PodcastIds::normalizeFeedUrl)
            .filterNot { PodcastIds.normalizeFeedUrl(it) == PodcastIds.normalizeFeedUrl(newer.feedUrl) }
        result[i] = newer.copy(
            showId = a.showId,
            previousFeedUrls = urls,
            followedAt = followedAt,
            unfollowedAt = unfollowedAt?.takeIf { it >= followedAt },
            sort = if (other.updatedAt > a.updatedAt) other.sort else a.sort,
            updatedAt = maxOf(a.updatedAt, other.updatedAt),
        )
    }
    return result
}

/**
 * Reads and writes podcasts.json. A missing file reads as an empty list. A file that can't
 * be read is moved aside to `podcasts.broken-<time>.json` first, so a save never writes over
 * a list that could still be rescued.
 */
class SubscriptionsFile(private val file: File) {

    val lastModified: Long get() = file.lastModified()

    fun load(): SubscriptionsData {
        val text = AtomicFile.readTextOrNull(file) ?: return SubscriptionsData()
        return decode(text) ?: run {
            file.renameTo(File(file.parentFile, "podcasts.broken-${System.currentTimeMillis()}.json"))
            SubscriptionsData()
        }
    }

    fun save(data: SubscriptionsData) = AtomicFile.writeText(file, json.encodeToString(SubscriptionsData.serializer(), data))

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun decode(text: String): SubscriptionsData? = try {
            json.decodeFromString(SubscriptionsData.serializer(), text.removePrefix("﻿"))
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * The follow list. Every change is written straight away (atomically), so call from a
 * background thread. [shows] holds every followed show, A–Z by title; unfollowed shows stay
 * in the file only.
 *
 * Podcasts.cmd never writes podcasts.json itself: it drops [inboxFile]
 * (podcasts_from_pc.json), which [mergeInbox] folds in and deletes. So Listen being open
 * during a PC sync can't lose either side's changes.
 */
class SubscriptionStore(
    file: File,
    private val inboxFile: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val store = SubscriptionsFile(file)
    private var all: List<Subscription> = store.load().shows
    private val _shows = MutableStateFlow(followed(all))
    val shows: StateFlow<List<Subscription>> = _shows.asStateFlow()

    @Synchronized
    fun all(): List<Subscription> = all

    @Synchronized
    fun get(showId: String): Subscription? = all.firstOrNull { it.showId == showId }

    /** The followed show with this address (by any address it has had), or null. */
    @Synchronized
    fun findByUrl(feedUrl: String): Subscription? {
        val n = PodcastIds.normalizeFeedUrl(feedUrl)
        return all.firstOrNull { n in it.knownUrls }
    }

    /**
     * Follows the show at [feedUrl] (again, if it was unfollowed: [Subscription.followedAt]
     * becomes now). Returns it. Following a show already followed changes nothing.
     */
    @Synchronized
    fun follow(feedUrl: String, title: String = "", author: String = "", artUrl: String? = null): Subscription {
        val now = clock()
        val existing = findByUrl(feedUrl)
        val sub = when {
            existing == null -> Subscription(
                showId = PodcastIds.showIdFor(feedUrl),
                feedUrl = feedUrl.trim(),
                title = title,
                author = author,
                artUrl = artUrl,
                followedAt = now,
                updatedAt = now,
            )
            existing.following -> return existing
            else -> existing.copy(
                followedAt = now,
                unfollowedAt = null,
                title = title.ifEmpty { existing.title },
                author = author.ifEmpty { existing.author },
                artUrl = artUrl ?: existing.artUrl,
                updatedAt = now,
            )
        }
        put(sub)
        return sub
    }

    /** Unfollows (keeps a tombstone). Deleting the show's downloads is the caller's choice. */
    @Synchronized
    fun unfollow(showId: String) {
        val s = get(showId) ?: return
        if (!s.following) return
        val now = clock()
        put(s.copy(unfollowedAt = now, updatedAt = now))
    }

    @Synchronized
    fun setSort(showId: String, sort: EpisodeSort) {
        val s = get(showId) ?: return
        if (s.sort != sort) put(s.copy(sort = sort, updatedAt = clock()))
    }

    /**
     * Saves what a refresh learned: the feed's own title, author and art, and its new
     * address when it has moved for good (the old one is kept for matching).
     */
    @Synchronized
    fun updateFromFeed(showId: String, title: String, author: String, artUrl: String?, movedTo: String?) {
        val s = get(showId) ?: return
        val moved = movedTo?.takeIf { PodcastIds.normalizeFeedUrl(it) != PodcastIds.normalizeFeedUrl(s.feedUrl) }
        val updated = s.copy(
            title = title.ifEmpty { s.title },
            author = author.ifEmpty { s.author },
            artUrl = artUrl ?: s.artUrl,
            feedUrl = moved ?: s.feedUrl,
            previousFeedUrls = if (moved != null) (s.previousFeedUrls + s.feedUrl).distinct() else s.previousFeedUrls,
        )
        if (updated != s) put(updated.copy(updatedAt = clock()))
    }

    /** Folds in the PC's list if Podcasts.cmd left one, then deletes it. Returns whether anything changed. */
    @Synchronized
    fun mergeInbox(): Boolean {
        val text = AtomicFile.readTextOrNull(inboxFile) ?: return false
        val theirs = SubscriptionsFile.decode(text)
        if (theirs == null) {
            inboxFile.renameTo(File(inboxFile.parentFile, "podcasts_from_pc.broken-${clock()}.json"))
            return false
        }
        val merged = mergeSubscriptions(all, theirs.shows)
        val changed = merged != all
        if (changed) save(merged)
        inboxFile.delete()
        return changed
    }

    /** Reads the file again (after a restore from the PC). */
    @Synchronized
    fun reload() {
        all = store.load().shows
        _shows.value = followed(all)
    }

    private fun put(sub: Subscription) {
        val i = all.indexOfFirst { it.showId == sub.showId }
        save(if (i < 0) all + sub else all.toMutableList().also { it[i] = sub })
    }

    private fun save(list: List<Subscription>) {
        store.save(SubscriptionsData(shows = list))
        all = list
        _shows.value = followed(list)
    }

    private fun followed(list: List<Subscription>) =
        list.filter { it.following }.sortedBy { sortKey(it.title) }
}
