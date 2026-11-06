package com.thelightphone.adventure.engine

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Feeds src/test/replays/<name>.txt to a story with a fixed seed. Two runs must match each
 * other and the fingerprint in hashes.txt, so a change in any game's output fails the test.
 * Fingerprints are stored rather than transcripts because the game text is copyrighted.
 *
 * If a change to the output is intended, replace the old fingerprint with the new one the
 * failed test shows. Read the game's transcript with tools\play.ps1 -Commands first.
 */
class ReplayTest {
    private val replays = File("src/test/replays")
    private val hashes = File(replays, "hashes.txt").readLines()
        .associate { it.substringBefore(' ') to it.substringAfter(' ') }

    private fun play(story: File, name: String): RecordingIo {
        val commands = File(replays, "$name.txt").readLines()
        assertTrue(commands.size >= 15, "$name needs at least 15 commands")
        val io = RecordingIo(commands, slotsFromScript = true)
        try {
            ZMachine(Story(story.readBytes()), io, seed = 42).run()
        } catch (finished: ScriptFinished) {
            // The commands ran out, which is the normal end of a replay.
        }
        return io
    }

    private fun fingerprint(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun check(story: File, name: String) {
        if (!story.exists()) {
            println("Skipped replay of $name: ${story.path} is not here")
            return
        }
        val first = play(story, name)
        val second = play(story, name)
        assertEquals(emptyList(), first.failures)
        assertEquals(first.output.toString(), second.output.toString())
        assertEquals(hashes[name], fingerprint(first.output.toString()), "$name output changed")
    }

    @Test
    fun zorkReplays() = check(File("src/main/assets/zork1.dat"), "zork1")

    @Test
    fun lostPigReplays() = check(File("../story-files/LostPig.z8"), "LostPig")

    @Test
    fun anchorheadReplays() = check(File("../story-files/anchor.z8"), "anchor")
}
