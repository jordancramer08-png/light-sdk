package com.thelightphone.listen.playback

import com.thelightphone.listen.music.Song
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueOrderTest {

    private fun s(name: String) = Song("$name.mp3", 1, 1, name, "A", "A", "Album")
    private fun QueueOrder.names() = songs.map { it.title }

    private val abcd = QueueOrder(listOf(s("a"), s("b"), s("c"), s("d")), index = 1)

    @Test
    fun `play next goes right after the current song, add to queue at the end`() {
        assertEquals(listOf("a", "b", "x", "y", "c", "d"), abcd.insertingNext(listOf(s("x"), s("y"))).names())
        assertEquals(listOf("a", "b", "c", "d", "x"), abcd.appending(listOf(s("x"))).names())
        assertEquals(1, abcd.insertingNext(listOf(s("x"))).index)
    }

    @Test
    fun `removing and moving never touch the current song`() {
        assertEquals(abcd, abcd.removingAt(1))
        val removed = abcd.removingAt(0)
        assertEquals(listOf("b", "c", "d"), removed.names())
        assertEquals(0, removed.index)
        assertEquals(listOf("a", "b", "d", "c"), abcd.moving(2, up = false).names())
        assertEquals(abcd, abcd.moving(2, up = true))
        assertEquals(listOf("a", "b"), abcd.clearingUpNext().names())
    }

    @Test
    fun `shuffle keeps the current song first and shuffle off goes back`() {
        val shuffled = abcd.shuffled(Random(1))
        assertEquals("b", shuffled.current?.title)
        assertEquals(0, shuffled.index)
        assertEquals(setOf("a", "b", "c", "d"), shuffled.names().toSet())
        val edited = shuffled.appending(listOf(s("x"))).removingAt(shuffled.names().indexOf("c"))
        val back = edited.unshuffledOrder()
        assertEquals(listOf("a", "b", "d", "x"), back.names())
        assertEquals("b", back.current?.title)
        assertNull(back.unshuffled)
    }

    @Test
    fun `play next while shuffled also lands after the current song in the unshuffled order`() {
        val shuffled = abcd.shuffled(Random(2)).insertingNext(listOf(s("x")))
        assertEquals("x", shuffled.songs[1].title)
        assertEquals(listOf("a", "b", "x", "c", "d"), shuffled.unshuffledOrder().names())
    }

    @Test
    fun `adding to a saved spot while a book plays`() {
        val spot = MusicState(paths = listOf("a", "b", "c"), index = 1)
        assertEquals(listOf("a", "b", "x", "c"), spot.adding(listOf("x"), next = true).paths)
        assertEquals(listOf("a", "b", "c", "x"), spot.adding(listOf("x"), next = false).paths)
        assertEquals(listOf("x"), MusicState().adding(listOf("x"), next = true).paths)
    }

    @Test
    fun `a restored queue with the same song twice keeps the right place`() {
        val state = MusicState(paths = listOf("a", "b", "a"), index = 2)
        assertEquals(2, state.restorable { true }!!.index)
    }
}
