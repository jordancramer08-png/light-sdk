package com.thelightphone.reader.pc

import com.thelightphone.reader.comics.ColorImage
import com.thelightphone.reader.comics.PixelRect
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max

/**
 * Before / after pictures of a few pages of one comic, for looking at on the PC:
 * `<folder>/<comic>/<page>-1-before.jpg`, `-2-after.jpg` and `-3-panels.jpg` (the final page
 * with its panels boxed and numbered left to right as found). Each is at most 1200 px long.
 */
class Samples(folder: File, comicName: String) {
    private val dir = File(folder, safeName(comicName))
    private var count = 0

    fun add(pageName: String, before: ColorImage, after: ColorImage, panels: List<PixelRect>) {
        dir.mkdirs()
        val base = safeName(pageName.substringAfterLast('/').substringBeforeLast('.'))
        write(PageImages.shrink(before, SAMPLE_SIDE), File(dir, "$base-1-before.jpg"))
        write(PageImages.shrink(after, SAMPLE_SIDE), File(dir, "$base-2-after.jpg"))
        writePanels(after, panels, File(dir, "$base-3-panels.jpg"))
        count++
    }

    fun finish() = Unit

    private fun write(image: ColorImage, file: File) = file.writeBytes(PageImages.jpeg(image, SAMPLE_QUALITY))

    private fun writePanels(page: ColorImage, panels: List<PixelRect>, file: File) {
        val small = PageImages.shrink(page, SAMPLE_SIDE)
        val scale = small.width.toDouble() / page.width
        val image = BufferedImage(small.width, small.height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, small.width, small.height, small.argb, 0, small.width)
        val g = image.createGraphics()
        g.stroke = BasicStroke(4f)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 36)
        if (panels.isEmpty()) {
            g.color = Color(200, 0, 0)
            g.drawString("No panels: shown whole", 20, 50)
        }
        panels.forEachIndexed { i, p ->
            val x = (p.left * scale).toInt()
            val y = (p.top * scale).toInt()
            g.color = Color(230, 0, 120)
            g.drawRect(x, y, max(1, (p.width * scale).toInt()), max(1, (p.height * scale).toInt()))
            g.color = Color(0, 0, 0, 170)
            g.fillRect(x + 4, y + 4, 46, 44)
            g.color = Color.YELLOW
            g.drawString("${i + 1}", x + 10, y + 40)
        }
        g.dispose()
        ImageIO.write(image, "jpeg", file)
    }

    companion object {
        private const val SAMPLE_SIDE = 1200
        private const val SAMPLE_QUALITY = 85

        fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9 ._()#-]"), "_").take(80).trim()
    }
}
