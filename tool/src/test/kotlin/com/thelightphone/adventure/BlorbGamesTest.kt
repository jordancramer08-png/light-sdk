package com.thelightphone.adventure

import com.thelightphone.adventure.engine.Story
import com.thelightphone.adventure.engine.storyFromBlorb
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The two real Blorb games. story-files is not in Git, so these skip on a fresh clone. */
class BlorbGamesTest {
    private val root: File = Files.createTempDirectory("adventure-blorb").toFile()
    private val galatea = File("../story-files/Galatea.zblorb")
    private val savoirFaire = File("../story-files/Savoir-Faire.zblorb")
    private val running = ArrayList<GameViewModel>()

    @AfterTest
    fun cleanUp() {
        running.forEach { game ->
            game.stop()
            runBlocking { withTimeout(20_000) { game.awaitEnd() } }
        }
        root.deleteRecursively()
    }

    private fun bothPresent(): Boolean {
        val present = galatea.exists() && savoirFaire.exists()
        if (!present) println("Skipped Blorb games: not in story-files")
        return present
    }

    @Test
    fun theStoryChunkIsFoundAndIsAValidStory() {
        if (!bothPresent()) return
        listOf(galatea to 266240, savoirFaire to 309760).forEach { (file, size) ->
            val story = storyFromBlorb(file.readBytes())
            assertEquals(size, story.size, file.name)
            val header = Story(story).header
            assertEquals(8, header.version, file.name)
            assertEquals(header.checksum, header.computeChecksum(), file.name)
        }
    }

    @Test
    fun theyShowOnTheShelfLikeAnyOtherGame() {
        if (!bothPresent()) return
        val games = File(root, "games").also { it.mkdirs() }
        galatea.copyTo(File(games, galatea.name))
        savoirFaire.copyTo(File(games, savoirFaire.name))
        val shelf = ShelfViewModel(
            readAsset = { File("src/main/assets", it).readBytes() },
            gamesFolder = games,
            dataFolder = File(root, "data"),
            hasStorageAccess = { true },
            dispatcher = Dispatchers.Unconfined,
        )
        val entries = shelf.state.value.entries
        assertEquals(emptyList(), shelf.state.value.unreadable)
        val added = entries.filter { it.removable }
        assertEquals(listOf("Galatea", "Savoir-Faire"), added.map { it.title })
        assertTrue(added.all { it.version == 8 })
        assertNotEquals(added[0].key, added[1].key)
    }

    private fun play(file: File, vararg commands: String): GameUiState {
        val game = GameViewModel(file.readBytes(), File(root, "data"), seed = 42).also { running += it }
        fun await(count: Int) = runBlocking {
            withTimeout(20_000) { game.state.first { it.waitingForInput && it.commandsTaken == count } }
        }
        var state = await(0)
        commands.forEachIndexed { index, command ->
            game.send(command)
            state = await(index + 1)
        }
        return state
    }

    private fun GameUiState.text() = lines.joinToString("\n") { line -> line.joinToString("") { it.text } }

    @Test
    fun galateaPlays() {
        if (!galatea.exists()) return
        val text = play(galatea, "", "look", "x galatea", "talk to her").text()
        assertTrue("Galatea" in text, text)
    }

    @Test
    fun savoirFairePlays() {
        if (!savoirFaire.exists()) return
        val text = play(savoirFaire, "", "look", "inventory").text()
        assertTrue("Savoir-Faire" in text, text)
    }
}
