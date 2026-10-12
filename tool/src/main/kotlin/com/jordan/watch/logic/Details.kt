package com.jordan.watch.logic

/**
 * Everything the detail page shows for a movie or an episode, as plain lines. Lines that
 * would be empty are null and left off the page.
 */
data class Details(
    val id: String,
    val title: String,
    /** "2016 · 2h 19m · ★ 8.1 · R". */
    val facts: String?,
    val genres: String?,
    val overview: String?,
    val director: String?,
    val cast: String?,
    /** "1080p H.264 AAC · 2.1 GB". */
    val quality: String?,
    /** Relative to /sdcard/Watch, or null. */
    val poster: String?,
    val runtimeMin: Int?,
    val isMovie: Boolean,
)

object DetailsMath {

    fun forMovie(entry: MovieEntry): Details {
        val m = entry.movie
        return Details(
            id = m.id,
            title = m.title,
            facts = join(m.year?.toString(), m.runtimeMin?.let(Shelves::runtime), m.rating?.let(::stars), m.contentRating),
            genres = m.genres.joinToString(", ").ifEmpty { null },
            overview = m.overview.ifBlank { null },
            director = m.director?.let { "Directed by $it" },
            cast = m.cast.take(CAST_SHOWN).joinToString(", ").ifEmpty { null }?.let { "Starring $it" },
            quality = StorageMath.qualityLine(m.height, m.videoCodec, m.audioCodec, m.bytes).ifEmpty { null },
            poster = m.poster?.let { "${entry.folder}/$it" },
            runtimeMin = m.runtimeMin,
            isMovie = true,
        )
    }

    /** An episode: the show's poster unless the episode has its own still. */
    fun forEpisode(show: ShowEntry, entry: EpisodeEntry): Details {
        val e = entry.episode
        return Details(
            id = e.id,
            title = e.title,
            facts = join("${show.show.title} · S${e.season} E${e.episode}", e.runtimeMin?.let(Shelves::runtime), e.airDate?.take(10)),
            genres = null,
            overview = e.overview.ifBlank { null },
            director = null,
            cast = null,
            quality = null,
            poster = e.still?.let { "${entry.folder}/$it" } ?: show.show.poster?.let { "${show.folder}/$it" },
            runtimeMin = e.runtimeMin,
            isMovie = false,
        )
    }

    /** "Play", or "Resume from 1:10:05" when started and not finished. */
    fun playLabel(position: Position?, watched: Boolean): String {
        val ms = position?.positionMs ?: 0
        return if (ms > 0 && !watched) "Resume from ${clock(ms)}" else "Play"
    }

    /** 4_205_000 -> "1:10:05"; 65_000 -> "1:05". */
    fun clock(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = (s % 60).toString().padStart(2, '0')
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
    }

    /** 8.1 -> "★ 8.1". The star is next to a number, so it never carries meaning alone. */
    fun stars(rating: Double): String {
        val tenths = Math.round(rating * 10)
        return "★ ${tenths / 10}.${tenths % 10}"
    }

    private fun join(vararg parts: String?): String? = parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { null }

    private const val CAST_SHOWN = 8
}
