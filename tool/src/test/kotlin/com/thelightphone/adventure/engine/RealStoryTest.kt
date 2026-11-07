package com.thelightphone.adventure.engine

import com.thelightphone.adventure.cli.storyInfo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Checks against the real games. Gradle runs tests from the tool folder. story-files is not in
 * Git, so a fresh clone has only the bundled Zork files and those games are reported as missing.
 */
class RealStoryTest {
    private data class Expected(val file: String, val version: Int, val release: Int, val serial: String)

    private val assets = File("src/main/assets")
    private val added = File("../story-files")

    private val bundled = listOf(
        Expected("zork1.dat", 3, 119, "880429"),
        Expected("zork2.dat", 3, 63, "860811"),
        Expected("zork3.dat", 3, 25, "860811"),
        Expected("zork1-bestiary.dat", 3, 1001, "261106"),
        Expected("zork2-bestiary.dat", 3, 1002, "261106"),
        Expected("zork3-bestiary.dat", 3, 1003, "261106"),
    )

    private val addedGames = listOf(
        Expected("adv550.z8", 8, 1, "160307"),
        Expected("advent.z8", 8, 1, "160307"),
        Expected("anchor.z8", 8, 5, "990206"),
        Expected("bluechairs.z5", 5, 2, "041229"),
        Expected("curses.z5", 5, 16, "951024"),
        Expected("dreamhold.z8", 8, 5, "041231"),
        Expected("huntdark.z5", 5, 4, "991119"),
        Expected("LostPig.z8", 8, 2, "080406"),
        Expected("photopia.z5", 5, 1, "120416"),
        Expected("shade.z5", 5, 3, "001127"),
        Expected("SoFar.z8", 8, 6, "961218"),
        Expected("Tangle.z5", 5, 4, "980226"),
        Expected("vespers.z8", 8, 1, "051128"),
        Expected("vgame.z8", 8, 1, "990831"),
    )

    // These two hold only Inform's four built-in objects and an empty dictionary.
    private val ownParserGames = setOf("adv550.z8", "advent.z8")

    private fun allGames(): List<Pair<Expected, Story>> {
        val found = bundled.map { it to Story(File(assets, it.file).readBytes()) }
        val present = addedGames.filter { File(added, it.file).isFile }
        if (present.size < addedGames.size) {
            println("story-files: only ${present.size} of ${addedGames.size} games found")
        }
        return found + present.map { it to Story(File(added, it.file).readBytes()) }
    }

    private fun zork1() = Story(File(assets, "zork1.dat").readBytes())

    private fun Story.findObject(name: String) =
        (1..objects.objectCount).first { objects.name(it) == name }

    @Test
    fun headersMatchTheKnownValues() {
        for ((expected, story) in allGames()) {
            assertEquals(expected.version, story.header.version, expected.file)
            assertEquals(expected.release, story.header.release, expected.file)
            assertEquals(expected.serial, story.header.serial, expected.file)
        }
    }

    @Test
    fun checksumsMatchTheHeader() {
        for ((expected, story) in allGames()) {
            assertEquals(story.header.checksum, story.header.computeChecksum(), expected.file)
        }
    }

    @Test
    fun everyObjectTreeIsConsistent() {
        for ((expected, story) in allGames()) {
            val objects = story.objects
            val count = objects.objectCount
            if (expected.file in ownParserGames) {
                assertEquals(4, count, expected.file)
            } else {
                assertTrue(count > 10, "${expected.file} has only $count objects")
            }
            for (obj in 1..count) {
                var child = objects.child(obj)
                var steps = 0
                while (child != 0) {
                    assertEquals(obj, objects.parent(child), "${expected.file} object $child")
                    child = objects.sibling(child)
                    assertTrue(++steps <= count, "${expected.file} object $obj has a child loop")
                }
            }
        }
    }

    @Test
    fun everyPropertyListWalksToItsEnd() {
        for ((expected, story) in allGames()) {
            val objects = story.objects
            for (obj in 1..objects.objectCount) {
                val numbers = objects.propertyNumbers(obj)
                assertEquals(numbers.sortedDescending(), numbers, "${expected.file} object $obj")
                if (expected.file == "curses.z5" && obj == 448) {
                    assertEquals(1, numbers.size - numbers.distinct().size)
                } else {
                    assertEquals(numbers.distinct(), numbers, "${expected.file} object $obj")
                }
                for (number in numbers) {
                    val length = objects.propertyLength(objects.propertyAddress(obj, number))
                    assertTrue(length in 1..64, "${expected.file} object $obj length $length")
                }
            }
        }
    }

    @Test
    fun everyDictionaryWordIsFoundAgain() {
        for ((expected, story) in allGames()) {
            val dictionary = story.dictionary
            if (expected.file in ownParserGames) {
                assertEquals(0, dictionary.size, expected.file)
            } else {
                assertTrue(dictionary.size > 100, expected.file)
            }
            for (index in 0 until dictionary.size) {
                // This key is "$#$" with the last character cut mid-escape, so it decodes as "$#".
                val word = if (expected.file == "LostPig.z8" && index == 0) "$#$" else dictionary.word(index)
                assertEquals(
                    dictionary.entryAddress(index),
                    dictionary.lookup(word),
                    "${expected.file} word '$word'",
                )
            }
        }
    }

    @Test
    fun zorkNamesMatchItsSource() {
        val story = zork1()
        val mailbox = story.findObject("small mailbox")
        assertEquals("West of House", story.objects.name(story.objects.parent(mailbox)))
        val leaflet = story.findObject("leaflet")
        assertEquals(mailbox, story.objects.parent(leaflet))
    }

    @Test
    fun zorkDictionaryKnowsItsWords() {
        val dictionary = zork1().dictionary
        assertNotEquals(0, dictionary.lookup("mailbox"))
        assertNotEquals(0, dictionary.lookup("XYZZY"))
        assertEquals(0, dictionary.lookup("telephone"))
        val tokens = dictionary.tokenize("open the mailbox, then qqq")
        assertEquals(listOf("open", "the", "mailbox", ",", "then", "qqq"), tokens.map { it.text })
        assertEquals(0, tokens.last().entryAddress)
        assertNotEquals(0, tokens.first().entryAddress)
    }

    @Test
    fun storyInfoPrintsAllSixLines() {
        val lines = storyInfo(zork1())
        assertEquals(6, lines.size)
        assertTrue(lines[0].endsWith("3"))
        assertTrue(lines[2].endsWith("880429"))
    }

    @Test
    fun engineHasNoAndroidImports() {
        val sources = File("src/main/kotlin/com/thelightphone/adventure/engine").listFiles().orEmpty()
        assertTrue(sources.isNotEmpty())
        for (file in sources) {
            val imports = file.readLines().filter { it.startsWith("import ") }
            val banned = imports.filter { "android" in it || "thelightphone.sdk" in it }
            assertEquals(emptyList(), banned, file.name)
        }
    }
}
