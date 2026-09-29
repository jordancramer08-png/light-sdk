package com.thelightphone.listen.storage

import com.thelightphone.listen.music.ListSort
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsFileTest {

    private val dir: File = Files.createTempDirectory("settings").toFile()

    @Test
    fun `sort choices survive a save and load`() {
        val file = SettingsFile(File(dir, "settings.json"))
        val settings = ListenSettings(
            songSort = ListSort("ARTIST", descending = true),
            albumSort = ListSort("ALBUM"),
            artistSort = ListSort("NAME", descending = true),
        )
        file.save(settings)
        assertEquals(settings, file.load())
    }

    @Test
    fun `missing, broken or partial files load with defaults`() {
        val path = File(dir, "settings.json")
        assertEquals(ListenSettings(), SettingsFile(path).load())
        path.writeText("{ not json")
        assertEquals(ListenSettings(), SettingsFile(path).load())
        path.writeText("""{ "songSort": { "field": "ALBUM" }, "someday": 1 }""")
        assertEquals(ListenSettings(songSort = ListSort("ALBUM")), SettingsFile(path).load())
    }
}
