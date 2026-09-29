package com.thelightphone.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Why a chapter is loaded. Only [MOVE] can mark the book Finished; [REPAGE] keeps the place exactly. */
enum class LoadReason { REOPEN, MOVE, REPAGE }

/** Load chapter [chapterIndex] and show the page holding character [offset]. */
data class ChapterLoad(val chapterIndex: Int, val offset: Int, val reason: LoadReason)

/** How a new page layout differs from the one before. */
enum class LayoutChange { FIRST, SAME, COLORS, METRICS }

/**
 * Keeps the reader's place (the chapter and character it is at, or going to) and starts the
 * chapter loads that show it (CLAUDE.md 7). A jump sets the place *before* its load starts,
 * so a re-page that cuts the load short starts that same load again, never the old place.
 * [L] is the page layout: PageLayout on the phone, something simpler in the PC tests.
 */
class ChapterLoader<L : Any>(
    private val scope: CoroutineScope,
    firstChapterIndex: Int,
    private val sameMetrics: (L, L) -> Boolean,
    private val load: suspend (L, ChapterLoad) -> Unit,
) {
    /** The chapter the reader is in, or going to. */
    var chapterIndex: Int = firstChapterIndex
        private set

    /** The character the reader is at, or going to: the first character of the page last turned or jumped to. */
    var offset: Int = 0
        private set

    private var layout: L? = null

    /** False until the saved place (or a jump) is known; nothing loads before that. */
    private var placeKnown = false

    /** The load under way, until it finishes. A re-page that interrupts it starts it again. */
    private var pending: ChapterLoad? = null
    private var job: Job? = null

    val isLoading: Boolean get() = job?.isActive == true

    /** The saved place, when the book opens. Ignored if the reader already jumped somewhere. */
    fun reopenAt(chapterIndex: Int, offset: Int) {
        if (!placeKnown) start(ChapterLoad(chapterIndex, offset, LoadReason.REOPEN))
    }

    /** A jump (Contents) or a page turn into another chapter. */
    fun moveTo(chapterIndex: Int, offset: Int) = start(ChapterLoad(chapterIndex, offset, LoadReason.MOVE))

    /** A page was turned to or jumped to: the place follows it. */
    fun pageShown(chapterIndex: Int, offset: Int) {
        this.chapterIndex = chapterIndex
        this.offset = offset
    }

    /**
     * The reading area's size or styles. An equal layout does nothing. Otherwise [beforeReload]
     * gets the kind of change (to drop what it made stale), then the place is loaded again.
     */
    fun configureLayout(new: L, beforeReload: (LayoutChange) -> Unit = {}) {
        val previous = layout
        val change = when {
            previous == null -> LayoutChange.FIRST
            new == previous -> LayoutChange.SAME
            sameMetrics(new, previous) -> LayoutChange.COLORS
            else -> LayoutChange.METRICS
        }
        if (change == LayoutChange.SAME) return
        layout = new
        beforeReload(change)
        if (placeKnown) run(pending ?: ChapterLoad(chapterIndex, offset, LoadReason.REPAGE))
    }

    private fun start(request: ChapterLoad) {
        placeKnown = true
        chapterIndex = request.chapterIndex
        offset = request.offset
        pending = request
        run(request)
    }

    private fun run(request: ChapterLoad) {
        val layout = layout ?: return // starts once the layout is known
        job?.cancel()
        job = scope.launch {
            load(layout, request)
            if (pending === request) pending = null
        }
    }
}
