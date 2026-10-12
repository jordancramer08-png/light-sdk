package com.jordan.watch.logic

import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowseTest {

    private fun movie(title: String, year: Int, genres: List<String> = emptyList(), runtime: Int? = 100, collection: String? = null, n: Int? = null) =
        MovieEntry(
            folder = "Movies/${collection ?: "_Singles"}/$title ($year)", video = "movie.mp4", fingerprint = "x",
            movie = Movie(id = "${collection ?: "_Singles"}/$title ($year)", title = title, year = year, genres = genres,
                runtimeMin = runtime, collection = collection, collectionNumber = n, cast = listOf("Zoë Kravitz")),
        )

    private fun episode(show: String, s: Int, e: Int, runtime: Int? = 30) = EpisodeEntry(
        folder = "TV/$show/Season 0$s", video = "S0${s}E0$e.mp4", fingerprint = "x",
        episode = Episode(id = "$show/S0${s}E0$e", title = "Ep $e", season = s, episode = e, runtimeMin = runtime),
    )

    private val bluey = ShowEntry(
        folder = "TV/Bluey", fingerprint = "x", show = Show(id = "Bluey", title = "Bluey", genres = listOf("Kids")),
        episodes = listOf(episode("Bluey", 1, 2), episode("Bluey", 1, 1), episode("Bluey", 2, 1), episode("Bluey", 0, 1)),
    )

    private val library = Library(
        LibraryIndex(
            movies = listOf(
                movie("The Matrix", 1999, listOf("Action")),
                movie("Up", 2009, listOf("Animation"), runtime = 96),
                movie("Alien", 1979, listOf("Horror", "Action"), runtime = 117),
                movie("Part Two", 2004, collection = "Saga", n = 2),
                movie("Part One", 2001, collection = "Saga", n = 1),
            ),
            shows = listOf(bluey),
        ),
    )

    private val matrix = "_Singles/The Matrix (1999)"

    @Test
    fun `movies tab filters, sorts both ways and remembers per tab`() {
        val byYear = TabView(sort = SortKey.Year, ascending = false)
        assertEquals(listOf("Up", "Part Two", "Part One", "The Matrix", "Alien"), Browse.tiles(BrowseTab.MOVIES, library, emptyMap(), emptyMap(), byYear).map { it.title })
        val action = TabView(filter = LibraryFilter(genre = "action"))
        assertEquals(listOf("Alien", "The Matrix"), Browse.tiles(BrowseTab.MOVIES, library, emptyMap(), emptyMap(), action).map { it.title })
        val settings = WatchSettings(tabViews = mapOf(BrowseTab.MOVIES.name to byYear))
        assertEquals(byYear, settings.tabView(BrowseTab.MOVIES))
        assertEquals(TabView(), settings.tabView(BrowseTab.TV))
    }

    @Test
    fun `watched filter and search use the state and cast`() {
        val watched = mapOf(matrix to 1L)
        val unwatched = TabView(filter = LibraryFilter(watchState = WatchState.Watched))
        assertEquals(listOf("The Matrix"), Browse.tiles(BrowseTab.MOVIES, library, emptyMap(), watched, unwatched).map { it.title })
        assertTrue(Browse.tiles(BrowseTab.MOVIES, library, emptyMap(), watched, TabView()).first { it.id == matrix }.watched)
        assertEquals(5, Browse.tiles(BrowseTab.MOVIES, library, emptyMap(), emptyMap(), TabView(), "zoe").size)
        assertEquals(listOf("Up"), Browse.tiles(BrowseTab.MOVIES, library, emptyMap(), emptyMap(), TabView(), "up zoe").map { it.title })
    }

    @Test
    fun `shows and collections add up their parts`() {
        val all = bluey.episodes.associate { it.episode.id to 1L }
        val some = mapOf("Bluey/S01E01" to 1L)
        assertEquals(WatchState.Watched, Browse.items(BrowseTab.TV, library, emptyMap(), all).single().watchState)
        assertEquals(WatchState.InProgress, Browse.items(BrowseTab.TV, library, emptyMap(), some).single().watchState)
        val saga = Browse.items(BrowseTab.COLLECTIONS, library, emptyMap(), emptyMap()).single()
        assertEquals("collection:Saga", saga.id)
        assertEquals(2001, saga.year)
        assertEquals(200, saga.runtimeMin)
        assertEquals(listOf("Saga"), Browse.tiles(BrowseTab.COLLECTIONS, library, emptyMap(), emptyMap(), TabView(ascending = false)).map { it.title })
    }

    @Test
    fun `titles waiting for removal are hidden`() {
        assertEquals(4, Browse.visible(library, listOf(matrix)).movies.size)
        assertEquals(library, Browse.visible(library, emptyList()))
    }

    @Test
    fun `sort labels name both directions`() {
        assertEquals("A–Z", SortLabels.order(SortKey.Title, true))
        assertEquals("Z–A", SortLabels.order(SortKey.Title, false))
        assertEquals("Newest first", SortLabels.order(SortKey.RecentlyAdded, false))
        assertEquals("Year · Newest first · 2 filters", SortLabels.summary(TabView(SortKey.Year, false, LibraryFilter(genre = "x", decade = 1990))))
        SortKey.entries.forEach { assertTrue(SortLabels.order(it, true) != SortLabels.order(it, false)) }
    }

    @Test
    fun `surprise picks only unwatched titles that fit`() {
        val items = Browse.items(BrowseTab.MOVIES, library, mapOf("_Singles/Up (2009)" to Position(1000)), mapOf(matrix to 1L))
        assertEquals(listOf("Alien", "Part Two", "Part One"), Surprise.candidates(items, null, null).map { it.title })
        assertEquals(listOf("Alien"), Surprise.candidates(items, "action", null).map { it.title })
        assertNull(Surprise.pick(items, "action", 90))
        repeat(20) { assertTrue(Surprise.pick(items, null, null, avoidId = "_Singles/Alien (1979)", random = Random(it))!!.title != "Alien") }
        assertEquals("Alien", Surprise.pick(items, "Horror", null, avoidId = "_Singles/Alien (1979)")?.title)
        assertEquals("Up to 1h 30m", Surprise.limitLabel(90))
    }

    @Test
    fun `seasons, episodes and collection lists`() {
        val seasons = TitleLists.seasons(library, bluey, mapOf("Bluey/S01E01" to 1L, "Bluey/S01E02" to 1L))
        assertEquals(listOf("Season 1", "Season 2", "Specials"), seasons.map { it.label })
        assertEquals("2 episodes · All watched", seasons[0].detail)
        assertTrue(seasons[0].allWatched)
        val episodes = TitleLists.episodes(library, bluey, 1, mapOf("Bluey/S01E02" to Position(15 * 60_000L)), mapOf("Bluey/S01E01" to 1L))
        assertEquals(listOf("1. Ep 1", "2. Ep 2"), episodes.map { it.title })
        assertTrue(episodes[0].watched)
        assertEquals(0.5f, episodes[1].progress)
        assertEquals(listOf("1. Part One", "2. Part Two"), TitleLists.collection(library, "Saga", emptyMap(), emptyMap()).map { it.title })
        assertEquals(listOf("Bluey", "The Matrix"), TitleLists.watchlist(library, listOf("Bluey", "gone", matrix), emptyMap(), emptyMap()).map { it.title })
    }

    @Test
    fun `detail lines and play label`() {
        val m = MovieEntry("Movies/_Singles/Up (2009)", "movie.mp4", "x",
            Movie(id = "_Singles/Up (2009)", title = "Up", year = 2009, runtimeMin = 96, rating = 8.25, contentRating = "PG",
                genres = listOf("Animation", "Family"), director = "Pete Docter", height = 1080, videoCodec = "h264", audioCodec = "aac", bytes = 2_100_000_000))
        val d = DetailsMath.forMovie(m)
        assertEquals("2009 · 1h 36m · ★ 8.3 · PG", d.facts)
        assertEquals("Animation, Family", d.genres)
        assertEquals("1080p H.264 AAC · 2.1 GB", d.quality)
        assertEquals("Directed by Pete Docter", d.director)
        assertNull(d.cast)
        assertEquals("Movies/_Singles/Up (2009)/poster.jpg", d.poster)
        assertEquals("Play", DetailsMath.playLabel(null, false))
        assertEquals("Resume from 1:10:05", DetailsMath.playLabel(Position(4_205_000), false))
        assertEquals("Play", DetailsMath.playLabel(Position(4_205_000), true))
        assertEquals("1:05", DetailsMath.clock(65_000))
    }

    @Test
    fun `accent comes from the poster's strongest hue`() {
        val red = (0xFF shl 24) or (200 shl 16) or (30 shl 8) or 30
        val grey = (0xFF shl 24) or (120 shl 16) or (120 shl 8) or 120
        val accent = AccentColor.pick(IntArray(80) { grey } + IntArray(20) { red })!!
        assertEquals(200, (accent shr 16) and 0xFF)
        assertTrue(((accent shr 8) and 0xFF) < 60)
        assertNull(AccentColor.pick(IntArray(100) { grey }))
        assertNull(AccentColor.pick(IntArray(0)))
    }

    private val dir: File = Files.createTempDirectory("watch-browse").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `removal requests and tab views survive a reload`() {
        val repos = Repositories(StateStore(dir))
        repos.removals.request(matrix)
        repos.removals.request(matrix)
        repos.settings.update { it.copy(subtitleSize = "LARGE", tabViews = mapOf("TV" to TabView(SortKey.Rating, false, LibraryFilter(quality = Quality.HD1080)))) }
        val again = Repositories(StateStore(dir)).also { it.load() }
        assertEquals(listOf(matrix), again.removals.ids.value)
        assertEquals("LARGE", again.settings.settings.value.subtitleSize)
        assertEquals(Quality.HD1080, again.settings.settings.value.tabView(BrowseTab.TV).filter.quality)
        again.removals.cancel(matrix)
        assertTrue(Repositories(StateStore(dir)).also { it.load() }.removals.ids.value.isEmpty())
    }
}
