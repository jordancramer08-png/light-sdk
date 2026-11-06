package com.thelightphone.adventure.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Plays the real games from the start to a few commands in. */
class PlayGameTest {
    private fun play(file: File, commands: List<String>): RecordingIo {
        val io = RecordingIo(commands)
        try {
            ZMachine(Story(file.readBytes()), io, seed = 42).run()
        } catch (finished: ScriptFinished) {
            // The commands ran out, which is the normal end of a test.
        }
        return io
    }

    @Test
    fun zorkOpeningPlaysThrough() {
        val commands = listOf("open the mailbox", "take the leaflet", "read it", "inventory", "go north")
        val io = play(File("src/main/assets/zork1.dat"), commands)
        val text = io.output.toString()
        assertEquals(emptyList(), io.failures)
        assertTrue("Opening the small mailbox reveals a leaflet." in text, text)
        assertTrue("Taken." in text, text)
        assertTrue("WELCOME TO ZORK!" in text, text)
        assertTrue("A leaflet" in text, text)
        assertTrue("North of House" in text, text)
        assertEquals("North of House", io.statusLines.last().location)
    }

    @Test
    fun everyGameStartsWithoutEngineErrors() {
        val folders = listOf(File("src/main/assets"), File("../story-files"))
        val stories = folders.flatMap { it.listFiles()?.toList() ?: emptyList() }
            .filter { it.extension in setOf("dat", "z5", "z8") }
        val problems = stories.flatMap { story ->
            val commands = listOf(" ", " ", " ", "look", "inventory", "examine me", "wait", "undo", "n")
            play(story, commands).failures.map { "${story.name}: $it" }
        }
        assertEquals(emptyList(), problems)
    }
}
