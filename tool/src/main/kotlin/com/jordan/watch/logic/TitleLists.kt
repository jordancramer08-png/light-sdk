package com.jordan.watch.logic

/** One season row on a show's page. */
data class SeasonRow(val season: Int, val label: String, val detail: String, val allWatched: Boolean)

/** The tiles and rows of the show, season, collection and Watchlist screens. Pure, no Android. */
object TitleLists {

    /** "Season 1", or "Specials" for season 0. */
    fun seasonName(season: Int): String = if (season == 0) "Specials" else "Season $season"

    /** Seasons in order (specials last), each with "10 episodes · 4 watched". */
    fun seasons(library: Library, show: ShowEntry, watched: Map<String, Long>): List<SeasonRow> =
        library.seasons(show).sortedBy { if (it == 0) Int.MAX_VALUE else it }.map { season ->
            val episodes = library.episodesOf(show, season)
            val seen = episodes.count { it.episode.id in watched }
            val all = seen == episodes.size && episodes.isNotEmpty()
            val count = if (episodes.size == 1) "1 episode" else "${episodes.size} episodes"
            SeasonRow(season, seasonName(season), count + if (all) " · All watched" else if (seen > 0) " · $seen watched" else "", all)
        }

    /** A season's episodes in order: "3. Title", "45m · aired 2019-10-01", watched marks and progress. */
    fun episodes(library: Library, show: ShowEntry, season: Int, positions: Map<String, Position>, watched: Map<String, Long>): List<Tile> =
        NextUp.episodeOrder(library.episodesOf(show, season).map { it.episode }).mapNotNull { e ->
            val entry = library.episode(e.id)?.second ?: return@mapNotNull null
            val ms = positions[e.id]?.positionMs ?: 0
            val isWatched = e.id in watched
            Tile(
                id = e.id,
                title = "${e.episode}. ${e.title}",
                detail = listOfNotNull(e.runtimeMin?.let(Shelves::runtime), e.airDate?.take(10)).joinToString(" · "),
                poster = e.still?.let { "${entry.folder}/$it" } ?: show.show.poster?.let { "${show.folder}/$it" },
                progress = if (ms > 0 && !isWatched) Resume.progress(ms, (e.runtimeMin ?: 0) * 60_000L) else null,
                watched = isWatched,
            )
        }

    /** A collection's films in story order, numbered: "1. Philosopher's Stone". */
    fun collection(library: Library, name: String, positions: Map<String, Position>, watched: Map<String, Long>): List<Tile> {
        val films = library.collections.firstOrNull { it.name == name }?.movies ?: return emptyList()
        return films.mapIndexed { i, entry ->
            val tile = Browse.movieTile(entry, positions, watched)
            tile.copy(title = "${entry.movie.collectionNumber ?: (i + 1)}. ${tile.title}")
        }
    }

    /** Watchlist titles in the order added (movies and shows); ids no longer on the phone are skipped. */
    fun watchlist(library: Library, ids: List<String>, positions: Map<String, Position>, watched: Map<String, Long>): List<Tile> {
        val shows = Shelves.shows(library).associateBy { it.id }
        return ids.mapNotNull { id -> library.movie(id)?.let { Browse.movieTile(it, positions, watched) } ?: shows[id] }
    }
}
