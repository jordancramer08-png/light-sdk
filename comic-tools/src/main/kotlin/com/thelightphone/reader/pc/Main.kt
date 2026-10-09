package com.thelightphone.reader.pc

import com.thelightphone.reader.comics.ColorImage
import com.thelightphone.reader.comics.PixelRect
import com.thelightphone.reader.comics.comicPages
import com.thelightphone.reader.comics.findCrop
import com.thelightphone.reader.comics.openComicZip
import com.thelightphone.reader.comics.rectToPage
import com.thelightphone.reader.comics.shrinkColorToLongSide
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

/**
 * The PC comic tool, run by scripts/send-comics.ps1 once per comic (CLAUDE.md 12, "PC comic tool"):
 *
 *     java -jar comic-tools.jar process <in.cbz> <out.cbz> <out.panels.json>
 *          [--crop] [--clean] [--shrink <longest side>] [--quality <JPEG quality>]
 *          [--samples <folder>] [--known-panels <panels.json>]
 *
 * Pages go through, in this order: crop the blank border (--crop), clean up the scan
 * (--clean: straighten, even out paper, deepen gray blacks), shrink (--shrink), then the
 * panels are found on the final page. A page that's changed is saved as a JPEG; everything
 * else in the CBZ is copied as it is. With none of --crop, --clean and --shrink, <out.cbz> is
 * "-" and nothing is written but the panels file. --known-panels: the panels were found before
 * for exactly these settings, so they're reused (only the file size is updated).
 * --samples: before / after pictures of a few pages, and the panels found, for checking on the PC.
 *
 * Prints "PAGE <n> <of>" as it goes (the script shows a progress bar), then a "DONE" line.
 *
 *     java -jar comic-tools.jar report <folder> <out folder> [comics] [pages per comic]
 *
 * For checking the panel finder: compares the phone's analysis with this tool's on pages spread
 * through each CBZ under the folder, and draws the pages where they differ.
 *
 *     java -jar comic-tools.jar version
 *
 * Prints [PanelsFile.TOOL_VERSION] (the send script keeps it in its cache keys).
 */
fun main(args: Array<String>) {
    try {
        when (args.firstOrNull()) {
            "process" -> process(ProcessOptions.parse(args.drop(1)))
            "report" -> Report.run(args.drop(1))
            "version" -> println(PanelsFile.TOOL_VERSION)
            else -> {
                System.err.println("Usage: process <in.cbz> <out.cbz|-> <out.panels.json> [options] | report <folder> <out>")
                exitProcess(2)
            }
        }
    } catch (e: Exception) {
        System.err.println("ERROR ${e.message ?: e.javaClass.simpleName}")
        exitProcess(1)
    }
}

class ProcessOptions(
    val input: File,
    val output: File?,
    val panels: File,
    val crop: Boolean,
    val clean: Boolean,
    val shrink: Int?,
    val quality: Int,
    val samples: File?,
    val knownPanels: File?,
) {
    val changesPages: Boolean get() = crop || clean || shrink != null

    companion object {
        fun parse(args: List<String>): ProcessOptions {
            require(args.size >= 3) { "process needs <in.cbz> <out.cbz|-> <out.panels.json>" }
            fun valueOf(flag: String): String? = args.indexOf(flag).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
            val options = ProcessOptions(
                input = File(args[0]),
                output = args[1].takeIf { it != "-" }?.let(::File),
                panels = File(args[2]),
                crop = "--crop" in args,
                clean = "--clean" in args,
                shrink = valueOf("--shrink")?.toInt(),
                quality = valueOf("--quality")?.toInt() ?: DEFAULT_QUALITY,
                samples = valueOf("--samples")?.let(::File),
                knownPanels = valueOf("--known-panels")?.let(::File),
            )
            require(!options.changesPages || options.output != null) { "pages are changed, so an output CBZ is needed" }
            return options
        }

        const val DEFAULT_QUALITY = 85
    }
}

/** What happened to one page. */
private class PageResult(val bytes: ByteArray, val entry: PanelsFile.PageEntry?, val changed: Boolean)

fun process(options: ProcessOptions) {
    val known = options.knownPanels?.takeIf { it.isFile }?.readText()
    openComicZip(options.input).use { zip ->
        val entries = zip.entries().toList()
        val pages = comicPages(entries.map { it.name })
        val pageSet = pages.toSet()
        val samplePages = samplePageNumbers(pages.size).map { pages[it] }.toSet()
        val samples = options.samples?.let { Samples(it, options.input.nameWithoutExtension) }
        val found = LinkedHashMap<String, PanelsFile.PageEntry>()
        var done = 0
        var changed = 0
        var unreadable = 0

        fun handle(name: String, bytes: ByteArray): ByteArray {
            val result = processPage(name, bytes, options, detect = known == null, firstPage = name == pages.first(), sample = samples.takeIf { name in samplePages })
            result.entry?.let { found[name] = it } ?: unreadable++
            if (result.changed) changed++
            done++
            println("PAGE $done ${pages.size}")
            return result.bytes
        }

        if (options.changesPages) {
            val output = options.output!!
            output.parentFile?.mkdirs()
            ZipOutputStream(output.outputStream().buffered()).use { out ->
                for (entry in entries) {
                    if (entry.isDirectory) continue
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val kept = if (entry.name in pageSet) handle(entry.name, bytes) else bytes
                    writeStored(out, entry.name, kept)
                }
            }
        } else if (known == null) {
            for (name in pages) handle(name, readEntry(zip, name))
        }

        val sentFile = options.output ?: options.input
        val json = if (known != null) {
            PanelsFile.withFileSize(known, sentFile.length()) ?: error("the saved panels file can't be read")
        } else {
            PanelsFile(sentFile.length(), options.crop, options.clean, pagesInOrder(pages, found)).toJson()
        }
        options.panels.parentFile?.mkdirs()
        options.panels.writeText(json)
        samples?.finish()
        val withPanels = found.values.count { it.panels.isNotEmpty() }
        println("DONE pages=${pages.size} withPanels=$withPanels changed=$changed unreadable=$unreadable reused=${known != null}")
    }
}

private fun pagesInOrder(pages: List<String>, found: Map<String, PanelsFile.PageEntry>) =
    LinkedHashMap<String, PanelsFile.PageEntry>().apply { for (p in pages) found[p]?.let { put(p, it) } }

private fun readEntry(zip: ZipFile, name: String): ByteArray = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }

/** Pages are stored uncompressed (pictures don't compress further), as the send script always did. */
private fun writeStored(out: ZipOutputStream, name: String, bytes: ByteArray) {
    val crc = CRC32().apply { update(bytes) }
    val entry = ZipEntry(name).apply {
        method = ZipEntry.STORED
        size = bytes.size.toLong()
        compressedSize = bytes.size.toLong()
        this.crc = crc.value
    }
    out.putNextEntry(entry)
    out.write(bytes)
    out.closeEntry()
}

/** Three pages spread through the comic (not the cover), for the before / after samples. */
fun samplePageNumbers(pageCount: Int): List<Int> = when {
    pageCount <= 0 -> emptyList()
    pageCount <= 3 -> (0 until pageCount).toList()
    else -> listOf(1, pageCount / 2, pageCount * 4 / 5).distinct()
}

/**
 * One page through the pipeline: crop, clean up, shrink (each only when asked and only when it
 * changes something), then its panels found on the final picture ([detect]). A picture that
 * can't be read is kept exactly as it was and gets no entry (the phone shows it whole).
 */
private fun processPage(name: String, bytes: ByteArray, options: ProcessOptions, detect: Boolean, firstPage: Boolean, sample: Samples?): PageResult {
    val original = PageImages.read(bytes) ?: return PageResult(bytes, null, changed = false)
    var image = original
    if (options.crop) image = cropped(image)
    if (options.clean) image = cleanedUp(image)
    options.shrink?.let { image = PageImages.shrink(image, it) }
    val changed = image !== original
    val out = if (changed) PageImages.jpeg(image, options.quality) else bytes
    val entry = if (detect) panelsOf(image, options.clean, firstPage) else PanelsFile.PageEntry(image.width, image.height, emptyList(), null, null)
    sample?.add(name, original, image, entry.panels)
    return PageResult(out, entry, changed)
}

/** The page without its blank border, found as the phone finds it (never more than 15% a side, 1% given back). */
fun cropped(image: ColorImage): ColorImage {
    val small = shrinkColorToLongSide(image)
    val crop = rectToPage(findCrop(small.gray()), small.width, small.height, image.width, image.height)
    if (crop.isEmpty || crop == PixelRect(0, 0, image.width, image.height)) return image
    return PageImages.cut(image, crop)
}

/** Straightened (when clearly tilted), paper evened out, gray blacks deepened; each step only when it's sure. */
fun cleanedUp(image: ColorImage): ColorImage {
    var page = image
    val small = shrinkColorToLongSide(page)
    val skew = ScanFixes.skewDegrees(small.gray())
    if (skew != 0.0) page = PageImages.rotated(page, -skew, ScanFixes.paperColor(small))
    ScanFixes.evenPaper(page, shrinkColorToLongSide(page))?.let { page = it }
    ScanFixes.deepenBlacks(page)?.let { page = it }
    return page
}

/** The final page looked at: panels (left to right), its remaining crop, and its levels unless it was cleaned up already. */
private fun panelsOf(image: ColorImage, cleaned: Boolean, firstPage: Boolean): PanelsFile.PageEntry {
    val small = shrinkColorToLongSide(image)
    val look = PanelFinder.find(small, image.width, image.height, firstPage).look
    val crop = look.crop.takeUnless { it == PixelRect(0, 0, image.width, image.height) }
    return PanelsFile.PageEntry(image.width, image.height, look.panels, crop, if (cleaned) null else look.levels)
}
