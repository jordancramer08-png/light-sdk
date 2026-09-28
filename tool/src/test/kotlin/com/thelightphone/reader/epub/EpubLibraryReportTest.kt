package com.thelightphone.reader.epub

import com.thelightphone.reader.contentsRows
import com.thelightphone.reader.data.ChapterMeta
import java.io.File
import kotlin.test.Test

/**
 * Parses every real EPUB under the folder named by READER_TEST_EPUBS (recursively)
 * and writes a report to build/epub-report.txt: one summary line per book, then
 * each book's chapter tree as the Contents screen shows it, with char and style counts. Skipped when the env var is unset,
 * so it never runs on CI or a plain `testDebugUnitTest`. See CLAUDE.md section 10,
 * phase 1.
 */
class EpubLibraryReportTest {

    @Test
    fun reportAgainstRealLibrary() {
        val dirPath = System.getenv("READER_TEST_EPUBS") ?: return
        val root = File(dirPath)
        check(root.isDirectory) { "READER_TEST_EPUBS is not a directory: $dirPath" }

        val epubFiles = root.walkTopDown()
            .filter { it.isFile && it.extension.equals("epub", ignoreCase = true) }
            .sortedBy { it.path }
            .toList()

        val summaryLines = mutableListOf<String>()
        val detailBlocks = mutableListOf<String>()
        var failureCount = 0

        for (file in epubFiles) {
            try {
                val book = EpubParser.parse(file)
                val notes = book.chapters.flatMap { c -> c.styles.filter { it.style == TextStyleKind.NOTE } }
                summaryLines.add("${file.name}\t\"${book.title}\" by ${book.author}\t${book.chapters.size} chapters\t${notes.size} notes")

                val detail = StringBuilder()
                detail.append("== ${file.name} ==\n")
                detail.append("Title: ${book.title}\nAuthor: ${book.author}\n")
                for (line in chapterTree(book)) detail.append("  $line\n")
                for (note in notes.take(SAMPLE_NOTES)) detail.append("  Note: ${note.note.orEmpty().replace("\n", " ").take(120)}\n")
                detailBlocks.add(detail.toString())
            } catch (e: DrmProtectedException) {
                summaryLines.add("${file.name}\tCan't open (DRM)")
                failureCount++
            } catch (e: Exception) {
                summaryLines.add("${file.name}\tERROR: ${e.message}")
                failureCount++
            }
        }

        val report = buildString {
            append("READER_TEST_EPUBS report\n")
            append("Source: $dirPath\n")
            append("${epubFiles.size} EPUB(s) found, $failureCount failed/skipped\n\n")
            append(summaryLines.joinToString("\n"))
            append("\n\n")
            append(detailBlocks.joinToString("\n"))
        }

        val outFile = File("build/epub-report.txt")
        outFile.parentFile?.mkdirs()
        outFile.writeText(report)

        println("Wrote EPUB report for ${epubFiles.size} books (${failureCount} failed) to ${outFile.absolutePath}")
    }

    /**
     * The book's rows as the Contents screen shows them: two spaces per level, "#" before a
     * heading, and for each chapter its length and styles ("412 chars, 3 italic").
     */
    private fun chapterTree(book: Book): List<String> {
        val metas = book.chapters.mapIndexed { i, c ->
            ChapterMeta(i + 1, c.title, "", c.text.length, depth = c.depth, parents = c.parents)
        }
        val rows = contentsRows(metas)
        return rows.mapIndexed { i, row ->
            val isChapterRow = rows.getOrNull(i + 1)?.chapterIndex != row.chapterIndex
            val chapter = book.chapters[row.chapterIndex - 1]
            val mark = if (row.isHeading) "# " else ""
            val details = if (isChapterRow) "  (${chapter.text.length} chars${styleCounts(chapter)})" else ""
            "  ".repeat(row.depth) + mark + row.title + details
        }
    }

    private companion object {
        /** How many notes each book's details show, to check their text reads right. */
        const val SAMPLE_NOTES = 3
    }

    /** ", 12 italic, 3 scene break" — only the kinds the chapter has. */
    private fun styleCounts(chapter: Chapter): String {
        val counts = chapter.styles.groupingBy { it.style }.eachCount()
        return TextStyleKind.entries.filter { it in counts }.joinToString("") {
            ", ${counts[it]} ${it.name.lowercase().replace('_', ' ')}"
        }
    }
}
