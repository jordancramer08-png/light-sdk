package com.thelightphone.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The reader's place-keeping, driven the way ReaderScreenViewModel drives it: a stand-in
 * "load" that pages a fake 10,000-character chapter and waits until the test lets it finish.
 */
class ChapterLoaderTest {

    /** A stand-in page layout: [pageChars] characters fit on a page; [color] doesn't move page breaks. */
    private data class TestLayout(val pageChars: Int, val color: Int = 0)

    private class Reader {
        val loads = mutableListOf<ChapterLoad>()
        private val gates = mutableListOf<CompletableDeferred<Unit>>()

        /** (chapter, page index) on screen; page index 0 is page 1. */
        var shown: Pair<Int, Int>? = null

        val loader: ChapterLoader<TestLayout> = ChapterLoader(
            CoroutineScope(Dispatchers.Unconfined),
            firstChapterIndex = 1,
            sameMetrics = { a, b -> a.pageChars == b.pageChars },
        ) { layout, request ->
            loads += request
            val gate = CompletableDeferred<Unit>().also { gates += it }
            gate.await() // the chapter is still being read and paged
            val text = "x".repeat(CHAPTER_CHARS)
            val pages = (0 until CHAPTER_CHARS step layout.pageChars)
                .map { PageRange(it, minOf(it + layout.pageChars, CHAPTER_CHARS)) }
            val pageIndex = pageIndexFor(pages, text, request.offset)
            shown = request.chapterIndex to pageIndex
            if (request.reason != LoadReason.REPAGE) loader.pageShown(request.chapterIndex, pages[pageIndex].start)
        }

        /** Lets the newest load finish (or load [number], counting from 0). */
        fun finishLoad(number: Int = gates.lastIndex) = gates[number].complete(Unit)
    }

    /** A book open at chapter 3, page 5, with [layout]. */
    private fun openBook(layout: TestLayout = TestLayout(pageChars = 1000)): Reader {
        val reader = Reader()
        reader.loader.configureLayout(layout)
        reader.loader.reopenAt(3, 4500)
        reader.finishLoad()
        assertEquals(3 to 4, reader.shown)
        return reader
    }

    @Test
    fun jumpThenEqualAndChangedLayoutEndsOnTheJumpsFirstPage() {
        val reader = openBook()
        reader.loader.moveTo(5, 0)

        // Coming back from Contents: the same layout again does nothing.
        reader.loader.configureLayout(TestLayout(pageChars = 1000))
        assertEquals(2, reader.loads.size)

        // A real change before the jump's load finishes loads the jump's chapter again, not the old one.
        reader.loader.configureLayout(TestLayout(pageChars = 800))
        assertEquals(ChapterLoad(5, 0, LoadReason.MOVE), reader.loads.last())
        reader.finishLoad()

        assertEquals(5 to 0, reader.shown)
        assertEquals(5, reader.loader.chapterIndex)
        assertEquals(0, reader.loader.offset)
        assertTrue(!reader.loader.isLoading)
    }

    @Test
    fun aThemeChangeDuringAJumpAlsoEndsOnTheJump() {
        val reader = openBook()
        reader.loader.moveTo(5, 0)
        reader.loader.configureLayout(TestLayout(pageChars = 1000, color = 1))
        reader.finishLoad()
        assertEquals(5 to 0, reader.shown)
    }

    @Test
    fun theOldLoadNeverShowsOnceCancelled() {
        val reader = openBook()
        reader.loader.moveTo(5, 0)
        reader.loader.configureLayout(TestLayout(pageChars = 800))
        reader.finishLoad(number = 1) // the jump's first, cancelled load
        assertEquals(3 to 4, reader.shown)
        reader.finishLoad()
        assertEquals(5 to 0, reader.shown)
    }

    @Test
    fun rePagingAfterALoadKeepsTheExactPlace() {
        val reader = openBook()
        reader.loader.configureLayout(TestLayout(pageChars = 600))
        assertEquals(ChapterLoad(3, 4000, LoadReason.REPAGE), reader.loads.last())
        reader.finishLoad()
        assertEquals(3 to 6, reader.shown) // 4000 is on page 7 at 600 a page
        assertEquals(4000, reader.loader.offset)
    }

    @Test
    fun theSavedPlaceWaitsForTheLayout() {
        val reader = Reader()
        reader.loader.reopenAt(3, 4500)
        assertEquals(0, reader.loads.size)
        reader.loader.configureLayout(TestLayout(pageChars = 1000))
        assertEquals(ChapterLoad(3, 4500, LoadReason.REOPEN), reader.loads.single())
    }

    @Test
    fun aJumpBeforeTheSavedPlaceIsReadWins() {
        val reader = Reader()
        reader.loader.configureLayout(TestLayout(pageChars = 1000))
        reader.loader.moveTo(5, 0)
        reader.loader.reopenAt(3, 4500)
        reader.finishLoad()
        assertEquals(5 to 0, reader.shown)
    }

    @Test
    fun pageLayoutsBuiltFromTheSameValuesAreEqual() {
        fun layout(width: Int) = PageLayout(
            bodyStyle = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, textAlign = TextAlign.Start),
            headingStyle = TextStyle(fontSize = 24.sp, color = Color.Red),
            headingGapPx = 20,
            widthPx = width,
            heightPx = 1000,
            accent = Color.Red,
        )
        assertEquals(layout(900), layout(900))
        assertNotEquals(layout(900), layout(800))
    }

    private companion object {
        const val CHAPTER_CHARS = 10_000
    }
}
