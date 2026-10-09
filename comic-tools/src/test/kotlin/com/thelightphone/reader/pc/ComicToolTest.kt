package com.thelightphone.reader.pc

import com.thelightphone.reader.comics.ColorImage
import com.thelightphone.reader.comics.PageLevels
import com.thelightphone.reader.comics.PixelRect
import com.thelightphone.reader.comics.argbOf
import com.thelightphone.reader.comics.shrinkColorToLongSide
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComicToolTest {

    private val white = argbOf(255, 255, 255)
    private val black = argbOf(0, 0, 0)

    /** A white page with dark horizontal rules, tilted by [degrees] (going down to the right when positive). */
    private fun ruledPage(width: Int, height: Int, degrees: Double): ColorImage {
        val argb = IntArray(width * height) { white }
        val slope = Math.tan(Math.toRadians(degrees))
        for (rule in 1..12) {
            val y0 = rule * height / 14
            for (x in 40 until width - 40) {
                for (t in 0..2) {
                    val y = (y0 + x * slope).toInt() + t
                    if (y in 0 until height) argb[y * width + x] = black
                }
            }
        }
        return ColorImage(width, height, argb)
    }

    @Test
    fun aTiltedPageIsFoundAndStraightened() {
        val page = ruledPage(800, 1100, 1.0)
        val skew = ScanFixes.skewDegrees(page.gray())
        assertTrue(abs(skew - 1.0) <= 0.15, "found $skew")
        val straightened = PageImages.rotated(page, -skew, white)
        assertEquals(0.0, ScanFixes.skewDegrees(straightened.gray()))
    }

    @Test
    fun aStraightPageIsLeftAlone() {
        assertEquals(0.0, ScanFixes.skewDegrees(ruledPage(800, 1100, 0.0).gray()))
        // Nothing but paper: no ink to measure, no tilt.
        assertEquals(0.0, ScanFixes.skewDegrees(ColorImage(800, 1100, IntArray(800 * 1100) { white }).gray()))
    }

    @Test
    fun yellowedPaperIsEvenedOutWithoutDarkeningInk() {
        val paper = argbOf(225, 210, 160)
        val ink = argbOf(30, 30, 30)
        val argb = IntArray(400 * 600) { i -> if ((i / 400) % 50 < 3) ink else paper }
        val page = ColorImage(400, 600, argb)
        val even = assertNotNull(ScanFixes.evenPaper(page, shrinkColorToLongSide(page)))
        val p = even.argb[10 * 400 + 10]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        assertTrue(r > 225 && g > 210 && b > 160, "paper brightened: $r $g $b")
        assertTrue(maxOf(r, g, b) - minOf(r, g, b) < 225 - 160, "cast reduced: $r $g $b")
        val inked = even.argb[0]
        assertTrue(((inked shr 16) and 0xFF) < 60, "ink stays dark")
    }

    @Test
    fun aPageWithNoPaperIsLeftAlone() {
        val dark = ColorImage(400, 600, IntArray(400 * 600) { argbOf(40, 20, 60) })
        assertNull(ScanFixes.evenPaper(dark, shrinkColorToLongSide(dark)))
    }

    @Test
    fun grayBlacksAreDeepenedOnlyWhenGray() {
        val gray = ColorImage(100, 100, IntArray(10_000) { i -> if (i % 10 == 0) argbOf(50, 50, 50) else white })
        val deep = assertNotNull(ScanFixes.deepenBlacks(gray))
        assertTrue(((deep.argb[0] shr 16) and 0xFF) < 20)
        assertEquals(white, deep.argb[1])
        val alreadyBlack = ColorImage(100, 100, IntArray(10_000) { i -> if (i % 10 == 0) black else white })
        assertNull(ScanFixes.deepenBlacks(alreadyBlack))
    }

    @Test
    fun aBlankBorderIsCroppedButArtIsKept() {
        // A gray box of art in the middle of a wide white border.
        val argb = IntArray(1000 * 1400) { white }
        for (y in 200 until 1200) for (x in 150 until 850) argb[y * 1000 + x] = argbOf(90, 60, 30)
        val page = ColorImage(1000, 1400, argb)
        val cut = cropped(page)
        assertTrue(cut.width < 1000 && cut.height < 1400, "cropped to ${cut.width} x ${cut.height}")
        assertTrue(cut.width >= 700 && cut.height >= 1000, "no art cut: ${cut.width} x ${cut.height}")
        // Never more than 15% of a side.
        assertTrue(cut.width >= 1000 * 0.7 - 2 && cut.height >= 1400 * 0.7 - 2)
        // Art to the very edge: nothing is cropped.
        val full = ColorImage(1000, 1400, IntArray(1000 * 1400) { argbOf(150, 140, 130) }) // flat mid-gray art
        assertTrue(cropped(full) === full)
    }

    @Test
    fun shrinkingKeepsTheShape() {
        val big = ColorImage(3000, 4500, IntArray(3000 * 4500) { white })
        val small = PageImages.shrink(big, 2000)
        assertEquals(1333, small.width)
        assertEquals(2000, small.height)
        val already = ColorImage(800, 1200, IntArray(800 * 1200) { white })
        assertTrue(PageImages.shrink(already, 2000) === already)
    }

    @Test
    fun thePanelsFileIsValidAndCarriesTheFileSize() {
        val file = PanelsFile(
            fileSize = 1234,
            cropped = true,
            cleaned = false,
            pages = linkedMapOf(
                "p/01 \"a\".jpg" to PanelsFile.PageEntry(100, 200, listOf(PixelRect(1, 2, 50, 90)), null, PageLevels(1, 2, 3, 250, 251, 252)),
                "p/02.jpg" to PanelsFile.PageEntry(100, 200, emptyList(), PixelRect(5, 5, 95, 195), null),
            ),
        ).toJson()
        assertTrue(file.contains("\"fileSize\":1234"))
        assertTrue(file.contains("\"p/01 \\\"a\\\".jpg\":{\"pageWidth\":100"))
        assertTrue(file.contains("\"panels\":[{\"left\":1,\"top\":2,\"right\":50,\"bottom\":90}]"))
        assertTrue(file.contains("\"levels\":{\"black\":[1,2,3],\"white\":[250,251,252]}"))
        assertTrue(file.contains("\"crop\":{\"left\":5,\"top\":5,\"right\":95,\"bottom\":195},\"levels\":null"))
        val moved = assertNotNull(PanelsFile.withFileSize(file, 99))
        assertTrue(moved.contains("\"fileSize\":99,") && !moved.contains("1234"))
    }

    @Test
    fun samplesAreSpreadThroughTheComic() {
        assertEquals(listOf(1, 15, 24), samplePageNumbers(30))
        assertEquals(listOf(0, 1), samplePageNumbers(2))
        assertEquals(emptyList(), samplePageNumbers(0))
    }

    @Test
    fun aComicIsProcessedEndToEnd() {
        val dir = Files.createTempDirectory("comic-tool-test").toFile()
        try {
            val input = File(dir, "in.cbz")
            ZipOutputStream(input.outputStream()).use { zip ->
                for (n in 1..3) {
                    zip.putNextEntry(ZipEntry("pages/$n.png"))
                    val page = ColorImage(2400, 3600, IntArray(2400 * 3600) { i -> if (i % 7 == 0) black else white })
                    val png = java.io.ByteArrayOutputStream()
                    val buffered = java.awt.image.BufferedImage(page.width, page.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                    buffered.setRGB(0, 0, page.width, page.height, page.argb, 0, page.width)
                    javax.imageio.ImageIO.write(buffered, "png", png)
                    zip.write(png.toByteArray())
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("ComicInfo.xml"))
                zip.write("<ComicInfo/>".toByteArray())
                zip.closeEntry()
            }
            val output = File(dir, "out.cbz")
            val panels = File(dir, "out.panels.json")
            val samples = File(dir, "samples")
            process(ProcessOptions.parse(listOf(input.path, output.path, panels.path, "--shrink", "2000", "--samples", samples.path)))
            ZipFile(output).use { zip ->
                val names = zip.entries().toList().map { it.name }
                assertEquals(listOf("pages/1.png", "pages/2.png", "pages/3.png", "ComicInfo.xml"), names)
                val first = assertNotNull(PageImages.read(zip.getInputStream(zip.getEntry("pages/1.png")).readBytes()))
                assertEquals(2000, first.height)
                assertEquals(ZipEntry.STORED, zip.getEntry("pages/1.png").method)
            }
            val json = panels.readText()
            assertTrue(json.contains("\"fileSize\":${output.length()}"))
            assertTrue(json.contains("\"pages/3.png\":{\"pageWidth\":1333,\"pageHeight\":2000"))
            assertTrue(File(samples, "in").listFiles()!!.any { it.name.endsWith("-2-after.jpg") })

            // Sent again with the panels known: they're reused for the new file.
            val again = File(dir, "again.cbz")
            val againPanels = File(dir, "again.panels.json")
            process(ProcessOptions.parse(listOf(input.path, again.path, againPanels.path, "--shrink", "2000", "--known-panels", panels.path)))
            assertEquals(PanelsFile.withFileSize(json, again.length()), againPanels.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
