package com.thelightphone.listen.playback

import com.thelightphone.listen.music.Song
import kotlin.random.Random

/**
 * The music queue in the order it plays ([songs], with [index] the current song) and, while
 * shuffle is on, the order it goes back to when shuffle is turned off ([unshuffled]).
 * Listen shuffles the queue itself rather than letting the player pick a hidden order, so
 * the Queue screen always shows (and edits) exactly what plays next.
 */
data class QueueOrder(val songs: List<Song>, val index: Int, val unshuffled: List<Song>? = null) {

    val current: Song? get() = songs.getOrNull(index)

    /** [added] right after the current song (and after it in the unshuffled order too). */
    fun insertingNext(added: List<Song>): QueueOrder {
        val at = (index + 1).coerceIn(0, songs.size)
        return copy(
            songs = songs.take(at) + added + songs.drop(at),
            unshuffled = unshuffled?.let { base ->
                val here = current?.let { cur -> base.indexOfFirst { it.path == cur.path } } ?: -1
                val after = if (here >= 0) here + 1 else base.size
                base.take(after) + added + base.drop(after)
            },
        )
    }

    /** [added] at the end. */
    fun appending(added: List<Song>): QueueOrder =
        copy(songs = songs + added, unshuffled = unshuffled?.let { it + added })

    /** Without the song at [at]; the current song can't be removed this way. */
    fun removingAt(at: Int): QueueOrder {
        if (at !in songs.indices || at == index) return this
        return copy(songs = songs.filterIndexed { i, _ -> i != at }, index = if (at < index) index - 1 else index)
    }

    /**
     * The song at [at] swapped with the one above ([up]) or below it. Songs only move within
     * "up next" (or within the played ones), never past the current song.
     */
    fun moving(at: Int, up: Boolean): QueueOrder {
        val other = if (up) at - 1 else at + 1
        if (at !in songs.indices || other !in songs.indices || at == index || other == index) return this
        val moved = songs.toMutableList()
        moved[at] = songs[other]
        moved[other] = songs[at]
        return copy(songs = moved)
    }

    /** Only the played songs and the current one are left. */
    fun clearingUpNext(): QueueOrder = copy(songs = songs.take(index + 1))

    /** Shuffle on: the current song stays first and every other song follows in random order. */
    fun shuffled(random: Random = Random.Default): QueueOrder {
        val cur = current ?: return this
        val others = songs.filterIndexed { i, _ -> i != index }.shuffled(random)
        return QueueOrder(songs = listOf(cur) + others, index = 0, unshuffled = unshuffled ?: songs)
    }

    /**
     * Shuffle off: back to the unshuffled order, keeping only the songs still in the queue
     * (removed ones stay gone, added ones that order doesn't know go at the end), on the
     * same current song.
     */
    fun unshuffledOrder(): QueueOrder {
        val base = unshuffled ?: return this
        val left = HashMap<String, Int>()
        songs.forEach { left[it.path] = (left[it.path] ?: 0) + 1 }
        val kept = base.filter { song ->
            val n = left[song.path] ?: 0
            if (n > 0) left[song.path] = n - 1
            n > 0
        }
        val extra = songs.filter { song ->
            val n = left[song.path] ?: 0
            if (n > 0) left[song.path] = n - 1
            n > 0
        }
        val result = kept + extra
        val cur = current
        val at = cur?.let { c -> result.indexOfFirst { it.path == c.path } }?.takeIf { it >= 0 } ?: 0
        return QueueOrder(songs = result, index = at, unshuffled = null)
    }
}
