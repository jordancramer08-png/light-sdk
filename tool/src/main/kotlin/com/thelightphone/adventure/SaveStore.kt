package com.thelightphone.adventure

import com.thelightphone.adventure.engine.Story
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.DateFormat
import java.util.Date

/** A named save slot. [savedAt] is null while the slot is empty. */
data class SlotInfo(val number: Int, val label: String, val savedAt: Long?)

/** Whether the slot picker is choosing where to save or what to restore. */
enum class SlotMode { SAVE, RESTORE }

/** The slot picker on screen. [confirming] is a filled slot waiting for "yes, overwrite". */
data class SlotPicker(
    val mode: SlotMode,
    val slots: List<SlotInfo>,
    val confirming: Int? = null,
    val askedByGame: Boolean = false,
)

/** Slot times as the phone's locale writes them. */
fun slotTime(savedAt: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(savedAt))

/** Names a game by release, serial and checksum, so two games never share a folder. */
fun gameKey(story: Story): String {
    val serial = story.header.serial.replace(Regex("[^A-Za-z0-9]"), "_")
    return "r${story.header.release}-$serial-${story.header.checksum.toString(16)}"
}

/** One game's saves: five slots and the automatic save, in a folder of their own. */
class SaveStore(root: File, key: String, private val clock: () -> Long = System::currentTimeMillis) {
    private val folder = File(root, "saves/$key")
    private val autoSave = File(folder, "auto.qzl")
    private val autoText = File(folder, "auto.txt")

    private fun slotFile(number: Int) = File(folder, "slot$number.qzl")

    private fun labelFile(number: Int) = File(folder, "slot$number.txt")

    fun slots(): List<SlotInfo> = (1..SLOT_COUNT).map(::slotInfo)

    private fun slotInfo(number: Int): SlotInfo {
        val file = slotFile(number)
        if (!file.exists()) return SlotInfo(number, "Empty", null)
        val lines = try {
            labelFile(number).readLines()
        } catch (error: IOException) {
            emptyList()
        }
        val savedAt = lines.firstOrNull()?.toLongOrNull() ?: file.lastModified()
        return SlotInfo(number, lines.getOrNull(1) ?: "Saved game", savedAt)
    }

    fun write(number: Int, data: ByteArray, label: String): Boolean =
        replace(slotFile(number), data) &&
            replace(labelFile(number), "${clock()}\n$label\n".toByteArray())

    fun read(number: Int): ByteArray? = readOrNull(slotFile(number))

    /** [text] is the end of the story so far, to show again on resuming. */
    fun writeAuto(data: ByteArray, text: String): Boolean =
        replace(autoSave, data) && replace(autoText, text.toByteArray())

    fun readAuto(): ByteArray? = readOrNull(autoSave)

    fun readAutoText(): String = readOrNull(autoText)?.toString(Charsets.UTF_8) ?: ""

    /** A game that has ended has nothing to resume. */
    fun clearAuto() {
        autoSave.delete()
        autoText.delete()
    }

    /** Removes every save for this game. */
    fun deleteAll() {
        folder.deleteRecursively()
    }

    private fun readOrNull(file: File): ByteArray? = try {
        file.readBytes()
    } catch (error: IOException) {
        null
    }

    /** Writes a new file first, so a failed save cannot damage the last good one. */
    private fun replace(file: File, data: ByteArray): Boolean = try {
        folder.mkdirs()
        val temporary = File(file.path + ".new")
        temporary.writeBytes(data)
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        true
    } catch (error: IOException) {
        false
    }

    companion object {
        const val SLOT_COUNT = 5
    }
}
