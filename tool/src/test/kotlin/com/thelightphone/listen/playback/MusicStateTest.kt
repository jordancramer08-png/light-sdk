package com.thelightphone.listen.playback

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MusicStateTest {

    private val dir: File = Files.createTempDirectory("listen-state").toFile()
    private val file = File(dir, ".state/music_state.json")
    private val store = MusicStateStore(file)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val state = MusicState(
        paths = listOf("A/X/1.mp3", "A/X/2.mp3", "B/Y/3.mp3", "B/Y/4.mp3"),
        index = 2,
        positionMs = 83_000,
        shuffle = true,
        repeat = MusicState.REPEAT_ALL,
    )

    @Test
    fun `saved state loads back and leaves no tmp file`() {
        store.save(state)
        assertEquals(state, store.load())
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
    }

    @Test
    fun `missing or broken file loads as null`() {
        assertNull(store.load())
        file.parentFile.mkdirs()
        file.writeText("{ not json")
        assertNull(store.load())
    }

    @Test
    fun `unknown and missing fields are tolerated`() {
        file.parentFile.mkdirs()
        file.writeText("""{"paths":["A/X/1.mp3"],"positionMs":5000,"futureField":1}""")
        assertEquals(MusicState(paths = listOf("A/X/1.mp3"), positionMs = 5000), store.load())
    }

    @Test
    fun `everything still on the phone restores at the same spot`() {
        assertEquals(state, state.restorable { true })
    }

    @Test
    fun `removed songs are dropped and the saved song keeps its position`() {
        val restored = state.restorable { it != "A/X/1.mp3" }!!
        assertEquals(listOf("A/X/2.mp3", "B/Y/3.mp3", "B/Y/4.mp3"), restored.paths)
        assertEquals(1, restored.index)
        assertEquals(83_000, restored.positionMs)
    }

    @Test
    fun `when the saved song is gone, the next song starts from the beginning`() {
        val restored = state.restorable { it != "B/Y/3.mp3" }!!
        assertEquals("B/Y/4.mp3", restored.paths[restored.index])
        assertEquals(0, restored.positionMs)
    }

    @Test
    fun `when the saved song was last and is gone, the new last song is chosen`() {
        val last = state.copy(index = 3)
        val restored = last.restorable { it != "B/Y/4.mp3" }!!
        assertEquals("B/Y/3.mp3", restored.paths[restored.index])
    }

    @Test
    fun `nothing left means nothing to restore`() {
        assertNull(state.restorable { false })
        assertNull(MusicState().restorable { true })
    }

    @Test
    fun `times format as minutes or hours`() {
        assertEquals("0:00", formatTime(0))
        assertEquals("1:23", formatTime(83_400))
        assertEquals("1:02:03", formatTime(3_723_000))
    }
}
