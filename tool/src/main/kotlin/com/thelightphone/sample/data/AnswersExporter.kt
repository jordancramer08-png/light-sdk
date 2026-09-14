package com.thelightphone.sample.data

import com.thelightphone.sdk.LightFileShare
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface ExportResult {
    data class Success(val fileName: String) : ExportResult
    data object ContentNotFound : ExportResult
    data class Failed(val message: String) : ExportResult
}

/**
 * Writes every saved answer out to one readable text file, in book order, so
 * Jordan can pull it off the phone with adb and keep it (CLAUDE.md 5).
 * Dropped into `lightContext.fileShare` - the only directory the `tool`
 * module can reach from outside its own app-private storage (NOTES.md 7) -
 * independent of the answers database itself.
 */
class AnswersExporter(
    private val studyContentRepository: StudyContentRepository,
    private val answersRepository: AnswersRepository,
    private val fileShare: LightFileShare,
) {

    fun export(): ExportResult {
        val content = (studyContentRepository.load() as? StudyContentResult.Loaded)?.content
            ?: return ExportResult.ContentNotFound

        val text = buildString {
            appendLine(content.title)
            appendLine("Exported ${EXPORT_DATE_FORMAT.format(Date())}")
            content.lessons.forEach { lesson ->
                appendLine()
                appendLesson(lesson)
            }
        }

        return try {
            fileShare.write(FILE_NAME) { it.write(text) }
            ExportResult.Success(FILE_NAME)
        } catch (e: IOException) {
            ExportResult.Failed(e.message ?: "Couldn't write the export file")
        }
    }

    private fun StringBuilder.appendLesson(lesson: Lesson) {
        appendLine("=".repeat(40))
        appendLine("Lesson ${lesson.lesson}: ${lesson.title}")
        if (lesson.passage.isNotBlank()) appendLine(lesson.passage)
        appendLine("=".repeat(40))

        lesson.items.forEach { item ->
            appendLine()
            appendLine(item.label())
            appendLine(answersRepository.getText(item.id).ifNullOrBlank("(no answer)"))
        }

        val notes = answersRepository.getText(lesson.notesId)
        if (!notes.isNullOrBlank()) {
            appendLine()
            appendLine("Notes:")
            appendLine(notes)
        }
    }

    private fun Item.label(): String = when (type) {
        ItemType.QUESTION -> "Question $number: $text"
        ItemType.CHILDRENS -> "Children's question: $text"
    }

    private fun String?.ifNullOrBlank(fallback: String): String =
        if (isNullOrBlank()) fallback else this

    companion object {
        const val FILE_NAME = "small_group_answers.txt"
        private val EXPORT_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}
