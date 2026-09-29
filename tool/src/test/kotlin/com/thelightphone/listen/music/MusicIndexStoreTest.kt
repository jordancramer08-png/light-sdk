package com.thelightphone.listen.music

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MusicIndexStoreTest {

    private val dir: File = Files.createTempDirectory("listen-index").toFile()
    private val file = File(dir, "cache/music_index.json")
    private val store = MusicIndexStore(file)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `saved index loads back`() {
        val index = MusicIndex(stamp = "s", songs = listOf(Song("A/B/1.mp3", 1, 2, "T", "A", "A", "B", track = 1, year = 1999)))
        store.save(index)
        assertEquals(index, store.load())
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
    }

    @Test
    fun `missing, broken or old index loads empty`() {
        assertEquals(MusicIndex(), store.load())
        file.parentFile.mkdirs()
        file.writeText("{ not json")
        assertEquals(MusicIndex(), store.load())
        file.writeText("""{"version":0,"stamp":"x","songs":[]}""")
        assertEquals(MusicIndex(), store.load())
    }

    @Test
    fun `unknown fields are ignored`() {
        file.parentFile.mkdirs()
        file.writeText("""{"version":1,"stamp":"x","extra":true,"songs":[]}""")
        assertEquals("x", store.load().stamp)
    }
}
