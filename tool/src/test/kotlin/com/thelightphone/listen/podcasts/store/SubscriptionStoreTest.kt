package com.thelightphone.listen.podcasts.store

import com.thelightphone.listen.podcasts.PodcastIds
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptionStoreTest {

    private val dir: File = Files.createTempDirectory("listen-podcasts").toFile()
    private val file = File(dir, ".state/podcasts.json")
    private val inbox = File(dir, ".state/podcasts_from_pc.json")
    private var now = 1_000L
    private fun store() = SubscriptionStore(file, inbox) { now }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `follow, list, and it's still there next time`() {
        val s = store()
        val sub = s.follow("https://feeds.example.com/rss", "Ask a Pastor", "Sample Ministries", "https://art/600.jpg")
        assertEquals(PodcastIds.showIdFor("https://feeds.example.com/rss"), sub.showId)
        assertEquals(1_000L, sub.followedAt)
        assertEquals(listOf("Ask a Pastor"), s.shows.value.map { it.title })
        assertEquals(listOf(sub), store().shows.value, "saved to the file")
        assertFalse(File(file.parentFile, "podcasts.json.tmp").exists())
    }

    @Test
    fun `the same feed written differently is one show`() {
        val s = store()
        val a = s.follow("https://feeds.example.com/rss")
        now = 2_000
        val b = s.follow("HTTP://Feeds.Example.com/rss/")
        assertEquals(a, b, "already followed: nothing changes")
        assertEquals(1, s.all().size)
    }

    @Test
    fun `shows list A to Z, ignoring The`() {
        val s = store()
        s.follow("https://a/1", "The Zebra Hour")
        s.follow("https://a/2", "apple talk")
        s.follow("https://a/3", "Middle")
        assertEquals(listOf("apple talk", "Middle", "The Zebra Hour"), s.shows.value.map { it.title })
    }

    @Test
    fun `unfollow keeps a tombstone, and following again starts a new followedAt`() {
        val s = store()
        val sub = s.follow("https://feeds.example.com/rss", "Show")
        now = 5_000
        s.unfollow(sub.showId)
        assertTrue(s.shows.value.isEmpty())
        assertEquals(5_000L, s.get(sub.showId)!!.unfollowedAt)
        now = 9_000
        val again = s.follow("https://feeds.example.com/rss")
        assertTrue(again.following)
        assertEquals(9_000L, again.followedAt)
        assertNull(again.unfollowedAt)
        assertEquals("Show", again.title, "details are kept")
    }

    @Test
    fun `sort choice and feed moves are saved, and the old address still matches`() {
        val s = store()
        val sub = s.follow("https://old.example.com/rss", "Show")
        s.setSort(sub.showId, EpisodeSort.OLDEST)
        s.updateFromFeed(sub.showId, "Show (renamed)", "Host", "https://art/new.jpg", movedTo = "https://new.example.com/rss")
        val reloaded = store().get(sub.showId)!!
        assertEquals(EpisodeSort.OLDEST, reloaded.sort)
        assertEquals("https://new.example.com/rss", reloaded.feedUrl)
        assertEquals(listOf("https://old.example.com/rss"), reloaded.previousFeedUrls)
        assertEquals(sub.showId, reloaded.showId, "the id never changes")
        assertEquals(sub.showId, store().findByUrl("http://old.example.com/rss/")?.showId)
    }

    @Test
    fun `the PC's list merges in without losing either side`() {
        val s = store()
        val a = s.follow("https://a.com/rss", "A") // followed on the phone at 1,000
        now = 2_000
        val b = s.follow("https://b.com/rss", "B")
        now = 3_000
        s.unfollow(b.showId) // unfollowed on the phone at 3,000

        // The PC: A unfollowed later (4,000); B "followed" at 2,500 (older than the phone's
        // unfollow, so it stays unfollowed); C new from the PC, written with http.
        inbox.writeText(
            SubscriptionsFile.json.encodeToString(
                SubscriptionsData.serializer(),
                SubscriptionsData(
                    shows = listOf(
                        a.copy(unfollowedAt = 4_000, updatedAt = 4_000),
                        Subscription(showId = b.showId, feedUrl = b.feedUrl, title = "B", followedAt = 2_500, updatedAt = 2_500),
                        Subscription(showId = PodcastIds.showIdFor("http://c.com/rss"), feedUrl = "http://c.com/rss", title = "C", followedAt = 3_500, updatedAt = 3_500),
                    ),
                ),
            ),
        )
        assertTrue(s.mergeInbox())
        assertFalse(inbox.exists(), "the inbox is deleted once merged")
        assertEquals(listOf("C"), s.shows.value.map { it.title })
        assertEquals(3, s.all().size)
        assertEquals(listOf("C"), store().shows.value.map { it.title }, "saved")
        assertFalse(s.mergeInbox(), "no inbox, nothing to do")
    }

    @Test
    fun `merging is the same whichever side goes first, and twice changes nothing`() {
        val phone = listOf(Subscription("id1", "https://a.com/rss", title = "A", followedAt = 10, updatedAt = 10))
        val pc = listOf(
            Subscription("other-id", "http://A.com/rss/", title = "A from PC", followedAt = 5, unfollowedAt = 20, updatedAt = 20),
            Subscription("id2", "https://b.com/rss", title = "B", followedAt = 7, updatedAt = 7),
        )
        val m1 = mergeSubscriptions(phone, pc)
        assertEquals(2, m1.size)
        val a = m1.first { it.title.startsWith("A") }
        assertEquals("id1", a.showId, "matched by address; the phone's id is kept")
        assertFalse(a.following, "the later unfollow wins")
        assertEquals(m1, mergeSubscriptions(m1, pc))
        assertEquals(m1.map { it.following }.sorted(), mergeSubscriptions(pc, phone).map { it.following }.sorted())
    }

    @Test
    fun `a broken file is set aside, never overwritten`() {
        file.parentFile.mkdirs()
        file.writeText("{ broken")
        val s = store()
        assertTrue(s.shows.value.isEmpty())
        assertNotNull(file.parentFile.listFiles()!!.firstOrNull { it.name.startsWith("podcasts.broken-") })
        inbox.writeText("also broken")
        assertFalse(s.mergeInbox())
        assertFalse(inbox.exists())
    }
}
