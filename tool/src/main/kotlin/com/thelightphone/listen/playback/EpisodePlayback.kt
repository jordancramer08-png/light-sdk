package com.thelightphone.listen.playback

import com.thelightphone.listen.books.Book
import com.thelightphone.listen.books.BookFile
import com.thelightphone.listen.books.ChapterMark
import com.thelightphone.listen.storage.ListenPaths
import java.io.File

/**
 * A downloaded podcast episode, ready to play (built by Podcasts from its download and its
 * saved place). [positionMs] and [lastPlayedAt] are where it was left; [played] means it was
 * finished, so it starts again from the beginning.
 */
data class EpisodeToPlay(
    val showId: String,
    val episodeId: String,
    val title: String,
    val showTitle: String,
    val audio: File,
    /** From the player when it has played before, else the feed's length (0 = unknown). */
    val durationMs: Long,
    val chapters: List<ChapterMark>,
    val positionMs: Long,
    val lastPlayedAt: Long?,
    val played: Boolean,
    val transcript: File?,
    val transcriptType: String?,
    /** Set when the episode isn't downloaded: the address it streams from ([audio] doesn't exist then). */
    val streamUrl: String? = null,
    /** Whether the feed offers a transcript (read online when it isn't downloaded). */
    val hasTranscript: Boolean = transcript != null,
)

/** The episode loaded in the player (its book stand-in is [PlaybackHub.book]). */
data class LoadedEpisode(
    val showId: String,
    val episodeId: String,
    val audio: File,
    /** Whether the episode has real chapter marks (otherwise Chapters is hidden). */
    val hasChapters: Boolean,
    val transcript: File?,
    val transcriptType: String?,
    /** The address it streams from, or null when it plays its download. */
    val streamUrl: String? = null,
    val hasTranscript: Boolean = transcript != null,
) {
    val streaming: Boolean get() = streamUrl != null

    fun isEpisode(showId: String, episodeId: String) = this.showId == showId && this.episodeId == episodeId
}

/** The id an episode's book stand-in has (also what music_state.json records as loaded). */
fun episodeBookId(showId: String, episodeId: String) = "$EPISODE_ID_PREFIX$showId/$episodeId"

const val EPISODE_ID_PREFIX = "podcast:"

/**
 * An episode plays through the audiobook path (speech audio, −15/+30, chapters, rewind after
 * a pause, saving every 10 s) as a one-file "book": the episode's title, the show as its
 * author, the chapter marks on its one file, and the show's art as its cover. Its folder is
 * written relative to Audiobooks/ ("../Podcasts/<showId>"), which is all the book code needs
 * to find the audio and the art.
 */
internal fun bookFor(e: EpisodeToPlay): Book {
    val showDir = e.audio.parentFile ?: ListenPaths.podcasts
    val folder = ListenPaths.audiobooks.toPath().relativize(showDir.toPath()).toString().replace('\\', '/')
    return Book(
        id = episodeBookId(e.showId, e.episodeId),
        folder = folder,
        title = e.title,
        author = e.showTitle,
        coverFile = "art.jpg",
        coverModified = File(showDir, "art.jpg").lastModified(),
        files = listOf(
            BookFile(
                path = e.audio.name,
                // With no chapter marks, the one "chapter" is named after the show, which is
                // what the now-playing bar shows under the title.
                label = e.showTitle,
                size = e.audio.length(),
                modified = e.audio.lastModified(),
                durationMs = e.durationMs,
                chapters = e.chapters,
            ),
        ),
        hasBookJson = false,
    )
}

internal fun EpisodeToPlay.loaded() =
    LoadedEpisode(showId, episodeId, audio, chapters.isNotEmpty(), transcript, transcriptType, streamUrl, hasTranscript)

/** Podcast speeds: 1.0× to 2.0× in steps of 0.1 (pitch stays natural at every one). */
val PODCAST_SPEEDS: List<Float> = (10..20).map { it / 10f }
