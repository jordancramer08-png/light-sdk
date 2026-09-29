package com.thelightphone.reader.comics

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * Runs the page analysis (crop, levels, both panel detectors: [analysePage]) on real comics
 * under the folder named by READER_TEST_COMICS (recursively) and writes, to
 * build/panel-report/<folder name>/, one overlay PNG per page, an index.html showing them all,
 * and summary.txt. Each overlay is the page cleaned up as Auto draws it, with the crop box as a
 * dashed yellow line, the panels as numbered boxes, and — when it fell back to the whole page —
 * red bands, a "0" and what the region detector found in thin gray. Each page is also run
 * through the old detector (XY-cut only, no crop or levels) so the summary shows both hit rates.
 * Takes up to [PAGES_PER_COMIC] pages spread through each comic. READER_TEST_COMICS_LIMIT caps
 * how many comics (the first ones by name). Skipped when READER_TEST_COMICS is unset.
 *
 * The Android test classpath has no javax.imageio, so pages are decoded by the PC helper
 * scripts/panel-report/PagePictures.java, run with this JDK's `java`. CBR files are unpacked
 * with 7-Zip first.
 */
class PanelReportTest {

    /** The detector's defaults, or others to try from READER_TEST_PANELS ("gutterShare=0.95,gutterTolerance=64"). */
    private val tuning: PanelTuning = run {
        val pairs = System.getenv("READER_TEST_PANELS").orEmpty().split(',').filter { '=' in it }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        val defaults = PanelTuning()
        PanelTuning(
            gutterShare = pairs["gutterShare"]?.toDouble() ?: defaults.gutterShare,
            gutterTolerance = pairs["gutterTolerance"]?.toInt() ?: defaults.gutterTolerance,
            minGutterShare = pairs["minGutterShare"]?.toDouble() ?: defaults.minGutterShare,
            minPanelShare = pairs["minPanelShare"]?.toDouble() ?: defaults.minPanelShare,
            speckInkShare = pairs["speckInkShare"]?.toDouble() ?: defaults.speckInkShare,
            minCoverage = pairs["minCoverage"]?.toDouble() ?: defaults.minCoverage,
            maxPanels = pairs["maxPanels"]?.toInt() ?: defaults.maxPanels,
            minPanelArea = pairs["minPanelArea"]?.toDouble() ?: defaults.minPanelArea,
            maxPanelArea = pairs["maxPanelArea"]?.toDouble() ?: defaults.maxPanelArea,
            minBorderShare = pairs["minBorderShare"]?.toDouble() ?: defaults.minBorderShare,
            bridgeShare = pairs["bridgeShare"]?.toDouble() ?: defaults.bridgeShare,
            edgeShare = pairs["edgeShare"]?.toDouble() ?: defaults.edgeShare,
            speckShare = pairs["speckShare"]?.toDouble() ?: defaults.speckShare,
            joinGapShare = pairs["joinGapShare"]?.toDouble() ?: defaults.joinGapShare,
            overlapShare = pairs["overlapShare"]?.toDouble() ?: defaults.overlapShare,
            minPanelFill = pairs["minPanelFill"]?.toDouble() ?: defaults.minPanelFill,
        )
    }

    private class PageResult(
        val comic: String,
        val page: String,
        val image: String,
        val panels: Int,
        val note: String,
        val oldPanels: Int = 0,
        val method: PanelMethod = PanelMethod.NONE,
        /** The share of the page's area the crop cut off. */
        val cropped: Double = 0.0,
    )

    @Test
    fun reportAgainstRealComics() {
        val dirPath = System.getenv("READER_TEST_COMICS") ?: return
        val root = File(dirPath)
        check(root.isDirectory) { "READER_TEST_COMICS is not a directory: $dirPath" }
        val limit = System.getenv("READER_TEST_COMICS_LIMIT")?.toIntOrNull() ?: Int.MAX_VALUE

        val comics = root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("cbz", "cbr") }
            .sortedWith { a, b -> naturalCompare(a.path, b.path) }
            .take(limit)
            .toList()

        val outDir = File("build/panel-report/${root.name}")
        outDir.deleteRecursively()
        outDir.mkdirs()

        val results = mutableListOf<PageResult>()
        comics.forEachIndexed { comicIndex, comic ->
            val temp = Files.createTempDirectory("panel-report").toFile()
            try {
                val pages = samplePages(comic, temp)
                decodePages(pages.map { it.second }, temp)
                pages.forEachIndexed { i, (name, _) ->
                    results.add(reportPage(comic.name, comicIndex + 1, name, File(temp, "$i.page"), outDir))
                }
            } catch (e: Exception) {
                results.add(PageResult(comic.name, "-", "", 0, "ERROR: ${e.message}"))
            } finally {
                temp.deleteRecursively()
            }
        }
        writeSummary(root, comics.size, results, outDir)
        writeIndex(results, outDir)
    }

    // --- getting the pages ---------------------------------------------------------------

    /** Up to [PAGES_PER_COMIC] pages spread evenly through the comic, each copied into [temp]: (name in the comic, file). */
    private fun samplePages(comic: File, temp: File): List<Pair<String, File>> {
        if (comic.extension.equals("cbr", ignoreCase = true)) {
            val unpacked = File(temp, "unpacked").apply { mkdirs() }
            run(listOf(SEVEN_ZIP, "x", "-y", "-o${unpacked.absolutePath}", comic.absolutePath))
            val names = unpacked.walkTopDown().filter { it.isFile }.map { it.relativeTo(unpacked).invariantSeparatorsPath }.toList()
            return spread(comicPages(names)).map { it to File(unpacked, it) }
        }
        return spread(readComicPages(comic)).mapIndexedNotNull { i, name ->
            val bytes = readComicEntry(comic, name, Long.MAX_VALUE) ?: return@mapIndexedNotNull null
            name to File(temp, "in-$i.${name.substringAfterLast('.')}").apply { writeBytes(bytes) }
        }
    }

    private fun <T> spread(items: List<T>): List<T> {
        if (items.size <= PAGES_PER_COMIC) return items
        return (0 until PAGES_PER_COMIC).map { items[it * (items.size - 1) / (PAGES_PER_COMIC - 1)] }.distinct()
    }

    /** Runs the ImageIO helper: picture number i becomes temp/i.page (see PagePictures.java). */
    private fun decodePages(pictures: List<File>, temp: File) {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val helper = File("../scripts/panel-report/PagePictures.java").absolutePath
        run(listOf(java, helper, temp.absolutePath) + pictures.map { it.absolutePath })
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(10, TimeUnit.MINUTES)) { "${command.first()} took too long" }
        if (output.isNotBlank()) println(output.trim())
    }

    // --- one page --------------------------------------------------------------------------

    private class Preview(val width: Int, val height: Int, val rgb: ByteArray)

    private fun reportPage(comic: String, comicNumber: Int, pageName: String, pageFile: File, outDir: File): PageResult {
        if (!pageFile.isFile) return PageResult(comic, pageName, "", 0, "can't decode")
        DataInputStream(pageFile.inputStream().buffered()).use { input ->
            val width = input.readInt()
            val height = input.readInt()
            val sampledWidth = input.readInt()
            val sampledHeight = input.readInt()
            val argb = IntArray(sampledWidth * sampledHeight) { input.readInt() }

            val started = System.nanoTime()
            val small = shrinkColorToLongSide(ColorImage(sampledWidth, sampledHeight, argb))
            val look = analysePage(small, width, height, tuning)
            val millis = (System.nanoTime() - started) / 1_000_000
            val old = detectPanels(small.gray(), width, height, tuning)
            val crop = look.crop
            val cropped = 1 - crop.width.toDouble() * crop.height / (width.toDouble() * height)
            val why = if (look.panels.isEmpty()) "${fallbackReason(look.candidates, crop)}: ${look.candidates.size} pieces, " else ""

            val imageName = "%03d-%s.png".format(comicNumber, File(pageName).nameWithoutExtension.take(40))
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val cleaned = cleanedUp(small, look.levels, CleanUp.AUTO)
            val preview = Preview(small.width, small.height, ByteArray(small.width * small.height * 3) { i ->
                (cleaned.argb[i / 3] shr (16 - 8 * (i % 3))).toByte()
            })
            val scale = small.width.toDouble() / width
            if (look.panels.isEmpty()) drawCandidates(preview, look.candidates, scale)
            drawCrop(preview, crop, scale)
            drawPanels(preview, look.panels, scale)
            File(outDir, imageName).writeBytes(png(preview))
            val note = "$why${look.method.name.lowercase()}, old ${old.size}, crop ${(cropped * 100).roundToInt()}%, " +
                "$millis ms, gutter ${look.gutter}, ${width}x$height"
            return PageResult(comic, pageName, imageName, look.panels.size, note, old.size, look.method, cropped)
        }
    }

    /** Why a page fell back to the whole page, in the words the summary counts (areas as shares of the crop). */
    private fun fallbackReason(candidates: List<PixelRect>, crop: PixelRect): String {
        val area = crop.width.toLong() * crop.height
        return when {
            candidates.size < 2 -> "no split"
            candidates.size > tuning.maxPanels -> "too many"
            candidates.any { it.width.toLong() * it.height < tuning.minPanelArea * area } -> "tiny panel"
            candidates.any { it.width.toLong() * it.height > tuning.maxPanelArea * area } -> "splash"
            else -> "low coverage"
        }
    }

    // --- drawing (pixel by pixel: no java.awt here) --------------------------------------------

    private fun drawPanels(preview: Preview, panels: List<PixelRect>, scale: Double) {
        panels.forEachIndexed { i, p ->
            val color = BOX_COLORS[i % BOX_COLORS.size]
            val left = (p.left * scale).roundToInt()
            val top = (p.top * scale).roundToInt()
            val right = (p.right * scale).roundToInt() - 1
            val bottom = (p.bottom * scale).roundToInt() - 1
            for (t in 0 until 4) {
                fill(preview, left, top + t, right, top + t, color)
                fill(preview, left, bottom - t, right, bottom - t, color)
                fill(preview, left + t, top, left + t, bottom, color)
                fill(preview, right - t, top, right - t, bottom, color)
            }
            label(preview, "${i + 1}", left, top, color)
        }
        if (panels.isEmpty()) {
            fill(preview, 0, 0, preview.width - 1, 5, FALLBACK_COLOR)
            fill(preview, 0, preview.height - 6, preview.width - 1, preview.height - 1, FALLBACK_COLOR)
            label(preview, "0", 0, 6, FALLBACK_COLOR)
        }
    }

    /** The crop box: a dashed yellow line 3 px wide (left out when nothing was cropped). */
    private fun drawCrop(preview: Preview, crop: PixelRect, scale: Double) {
        val left = (crop.left * scale).roundToInt()
        val top = (crop.top * scale).roundToInt()
        val right = minOf(preview.width - 1, (crop.right * scale).roundToInt() - 1)
        val bottom = minOf(preview.height - 1, (crop.bottom * scale).roundToInt() - 1)
        if (left <= 0 && top <= 0 && right >= preview.width - 2 && bottom >= preview.height - 2) return
        for (x in left..right step 12) {
            fill(preview, x, top, minOf(right, x + 6), top + 2, CROP_COLOR)
            fill(preview, x, bottom - 2, minOf(right, x + 6), bottom, CROP_COLOR)
        }
        for (y in top..bottom step 12) {
            fill(preview, left, y, left + 2, minOf(bottom, y + 6), CROP_COLOR)
            fill(preview, right - 2, y, right, minOf(bottom, y + 6), CROP_COLOR)
        }
    }

    /** On a page that fell back to the whole page, what the region detector found, in thin gray. */
    private fun drawCandidates(preview: Preview, candidates: List<PixelRect>, scale: Double) {
        for (p in candidates) {
            val left = (p.left * scale).roundToInt()
            val top = (p.top * scale).roundToInt()
            val right = (p.right * scale).roundToInt() - 1
            val bottom = (p.bottom * scale).roundToInt() - 1
            fill(preview, left, top, right, top + 1, CANDIDATE_COLOR)
            fill(preview, left, bottom - 1, right, bottom, CANDIDATE_COLOR)
            fill(preview, left, top, left + 1, bottom, CANDIDATE_COLOR)
            fill(preview, right - 1, top, right, bottom, CANDIDATE_COLOR)
        }
    }

    private fun fill(preview: Preview, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        for (y in max(0, top)..minOf(preview.height - 1, bottom)) {
            for (x in max(0, left)..minOf(preview.width - 1, right)) {
                val i = (y * preview.width + x) * 3
                preview.rgb[i] = (color shr 16).toByte()
                preview.rgb[i + 1] = (color shr 8).toByte()
                preview.rgb[i + 2] = color.toByte()
            }
        }
    }

    /** [text] (digits only) in white on a [color] block at [left], [top]; each font dot is 5 px. */
    private fun label(preview: Preview, text: String, left: Int, top: Int, color: Int) {
        val dot = 5
        fill(preview, left, top, left + text.length * 4 * dot + dot, top + 7 * dot, color)
        text.forEachIndexed { n, digit ->
            val rows = DIGITS[digit - '0']
            for (row in 0 until 5) {
                for (col in 0 until 3) {
                    if (rows[row][col] != '#') continue
                    val x = left + dot + n * 4 * dot + col * dot
                    val y = top + dot + row * dot
                    fill(preview, x, y, x + dot - 1, y + dot - 1, 0xFFFFFF)
                }
            }
        }
    }

    /** A plain 8-bit RGB PNG. */
    private fun png(preview: Preview): ByteArray {
        val raw = ByteArrayOutputStream()
        DeflaterOutputStream(raw).use { out ->
            for (y in 0 until preview.height) {
                out.write(0) // no filter
                out.write(preview.rgb, y * preview.width * 3, preview.width * 3)
            }
        }
        val header = ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeInt(preview.width)
                writeInt(preview.height)
                write(byteArrayOf(8, 2, 0, 0, 0))
            }
        }
        val file = ByteArrayOutputStream()
        file.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        chunk(file, "IHDR", header.toByteArray())
        chunk(file, "IDAT", raw.toByteArray())
        chunk(file, "IEND", ByteArray(0))
        return file.toByteArray()
    }

    private fun chunk(file: ByteArrayOutputStream, type: String, data: ByteArray) {
        val out = DataOutputStream(file)
        out.writeInt(data.size)
        val typeBytes = type.toByteArray()
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }
        out.writeInt(crc.value.toInt())
    }

    // --- summary --------------------------------------------------------------------------

    private fun writeSummary(root: File, comicCount: Int, results: List<PageResult>, outDir: File) {
        val pages = results.filter { it.image.isNotEmpty() }
        val withPanels = pages.count { it.panels > 0 }
        val oldWithPanels = pages.count { it.oldPanels > 0 }
        fun percent(n: Int) = if (pages.isEmpty()) 0 else (n * 100.0 / pages.size).roundToInt()
        val text = buildString {
            append("Panel report\nSource: ${root.path}\n$tuning\n")
            append("$comicCount comics, ${pages.size} pages analysed, ${results.size - pages.size} not readable\n")
            append("New: panels on $withPanels of ${pages.size} pages (${percent(withPanels)}%, ${pages.sumOf { it.panels }} panels): ")
            append("regions ${pages.count { it.method == PanelMethod.REGIONS }}, XY-cut ${pages.count { it.method == PanelMethod.XY_CUT }}\n")
            append("Old (XY-cut only, no crop or levels): panels on $oldWithPanels of ${pages.size} pages (${percent(oldWithPanels)}%)\n")
            append("Cropped: ${pages.count { it.cropped > 0.005 }} pages, on average ${(pages.map { it.cropped }.average().takeIf { !it.isNaN() } ?: 0.0).times(100).roundToInt()}% of the page\n")
            append("${pages.size - withPanels} pages fell back to the whole page\n")
            val reasons = pages.filter { it.panels == 0 }.groupingBy { it.note.substringBefore(':') }.eachCount()
            append("Fallbacks: ${reasons.entries.joinToString { "${it.key} ${it.value}" }}\n")
            var lastComic = ""
            for (r in results) {
                if (r.comic != lastComic) append("\n== ${r.comic} ==\n").also { lastComic = r.comic }
                val found = if (r.panels == 0) "whole page" else "${r.panels} panels"
                append("  ${r.image.ifEmpty { r.page }}\t$found\t${r.note}\n")
            }
        }
        File(outDir, "summary.txt").writeText(text)
        println(text.lineSequence().take(8).joinToString("\n"))
    }

    private fun writeIndex(results: List<PageResult>, outDir: File) {
        val cells = results.filter { it.image.isNotEmpty() }.joinToString("\n") { r ->
            val label = (if (r.panels == 0) "whole page" else "${r.panels} panels (${r.method.name.lowercase()})") + ", old ${r.oldPanels}"
            "<figure><a href=\"${r.image}\"><img src=\"${r.image}\" loading=\"lazy\"></a>" +
                "<figcaption>${r.image}: $label</figcaption></figure>"
        }
        File(outDir, "index.html").writeText(
            """<!doctype html><meta charset="utf-8"><title>Panel report</title>
            |<p>Pages drawn with Clean up scans: Auto. Dashed yellow: the crop. Numbered boxes: panels in reading order. Red bands: whole page (thin gray: what the region detector found).</p>
            |<style>body{font:14px sans-serif;background:#222;color:#eee}figure{display:inline-block;margin:6px;width:300px;vertical-align:top}
            |img{width:300px}figcaption{word-break:break-all}</style>
            |$cells
            |""".trimMargin(),
        )
    }

    companion object {
        private const val PAGES_PER_COMIC = 10
        private const val SEVEN_ZIP = "C:\\Program Files\\7-Zip\\7z.exe"
        private const val FALLBACK_COLOR = 0xC80000
        private const val CANDIDATE_COLOR = 0x808080
        private const val CROP_COLOR = 0xFFD200
        private val BOX_COLORS = listOf(0x0096FF, 0xFF7800, 0x00BE5A, 0xC800C8, 0xE61E3C)

        /** A 3 × 5 dot font for the panel numbers. */
        private val DIGITS = listOf(
            "###|#.#|#.#|#.#|###", ".#.|##.|.#.|.#.|###", "###|..#|###|#..|###", "###|..#|###|..#|###",
            "#.#|#.#|###|..#|..#", "###|#..|###|..#|###", "###|#..|###|#.#|###", "###|..#|..#|..#|..#",
            "###|#.#|###|#.#|###", "###|#.#|###|..#|###",
        ).map { it.split('|') }
    }
}
