package com.thelightphone.reader.pc

import com.thelightphone.reader.comics.analysePage
import com.thelightphone.reader.comics.comicPages
import com.thelightphone.reader.comics.openComicZip
import com.thelightphone.reader.comics.shrinkColorToLongSide
import java.io.File

/**
 * `report <folder> <out folder> [comics] [pages per comic]`: how the PC panel finder does
 * against the phone's own analysis, on pages spread through each CBZ under the folder. Writes
 * `summary.txt`, and for every page where the PC found panels the phone didn't, the page with its
 * panels drawn (`gained/`), so each gain can be checked by eye.
 */
object Report {
    fun run(args: List<String>) {
        require(args.size >= 2) { "report needs <folder> <out folder>" }
        val folder = File(args[0])
        val out = File(args[1]).apply { mkdirs() }
        val comicLimit = args.getOrNull(2)?.toInt() ?: Int.MAX_VALUE
        val perComic = args.getOrNull(3)?.toInt() ?: 10
        val comics = folder.walkTopDown().filter { it.isFile && it.extension.equals("cbz", ignoreCase = true) }
            .sortedBy { it.path }.take(comicLimit).toList()
        val gained = File(out, "gained")
        val lines = mutableListOf<String>()
        var pagesSeen = 0
        var phoneHits = 0
        var pcHits = 0
        val byHow = mutableMapOf<PanelFinder.How, Int>()
        for (comic in comics) {
            val samples = Samples(gained, comic.nameWithoutExtension)
            openComicZip(comic).use { zip ->
                val pages = comicPages(zip.entries().toList().map { it.name })
                val picks = (0 until perComic).map { it * pages.size / perComic }.distinct().filter { it < pages.size }
                for (index in picks) {
                    val name = pages[index]
                    val image = PageImages.read(zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }) ?: continue
                    val small = shrinkColorToLongSide(image)
                    val phone = analysePage(small, image.width, image.height).panels.size
                    val pc = PanelFinder.find(small, image.width, image.height, firstPage = index == 0)
                    pagesSeen++
                    if (phone > 0) phoneHits++
                    if (pc.look.panels.isNotEmpty()) pcHits++
                    byHow.merge(pc.how, 1, Int::plus)
                    lines += "${comic.name} | $name | phone $phone | pc ${pc.look.panels.size} (${pc.how}) gutter ${pc.look.gutter} index $index"
                    if (pc.how == PanelFinder.How.LOOSER || pc.how == PanelFinder.How.HALVES) {
                        samples.add(name, image, image, pc.look.panels)
                    }
                }
            }
            println("REPORT ${comic.name}")
        }
        val summary = buildString {
            appendLine("Comics: ${comics.size}, pages looked at: $pagesSeen")
            appendLine("Pages with panels, phone analysis: $phoneHits")
            appendLine("Pages with panels, PC finder:      $pcHits")
            appendLine("PC finder by method: $byHow")
            appendLine()
            lines.forEach(::appendLine)
        }
        File(out, "summary.txt").writeText(summary)
        println(summary.lines().take(4).joinToString("\n"))
    }
}
