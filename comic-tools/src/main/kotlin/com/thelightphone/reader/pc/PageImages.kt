package com.thelightphone.reader.pc

import com.thelightphone.reader.comics.ColorImage
import com.thelightphone.reader.comics.PixelRect
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reading and writing page pictures on the PC (Java's ImageIO: JPEG, PNG, GIF, BMP). A picture
 * ImageIO can't read (WebP, CMYK JPEG) is left exactly as it is, and that page gets no panels.
 */
object PageImages {

    /** The page's pixels, or null when ImageIO can't read it. */
    fun read(bytes: ByteArray): ColorImage? {
        val image = try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            null
        } ?: return null
        if (image.width <= 0 || image.height <= 0) return null
        val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        // Transparent pixels are shown on white, as a page would be.
        for (i in argb.indices) {
            val alpha = argb[i] ushr 24
            if (alpha < 255) argb[i] = onWhite(argb[i], alpha)
        }
        return ColorImage(image.width, image.height, argb)
    }

    /** The picture as a JPEG of [quality] (0 to 100). */
    fun jpeg(image: ColorImage, quality: Int): ByteArray {
        val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        buffered.setRGB(0, 0, image.width, image.height, image.argb, 0, image.width)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val params = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality / 100f
            }
            writer.write(null, IIOImage(buffered, null, null), params)
        }
        writer.dispose()
        return out.toByteArray()
    }

    /** The [area] of the picture (page pixels). */
    fun cut(image: ColorImage, area: PixelRect): ColorImage {
        if (area.left == 0 && area.top == 0 && area.width == image.width && area.height == image.height) return image
        val argb = IntArray(area.width * area.height)
        for (y in 0 until area.height) {
            System.arraycopy(image.argb, (area.top + y) * image.width + area.left, argb, y * area.width, area.width)
        }
        return ColorImage(area.width, area.height, argb)
    }

    /**
     * The picture made smaller so its longer side is [longSide], in good quality: halved step
     * by step (bilinear), then the last step bicubic. A picture already small enough is returned as it is.
     */
    fun shrink(image: ColorImage, longSide: Int): ColorImage {
        val long = max(image.width, image.height)
        if (long <= longSide) return image
        val scale = longSide.toDouble() / long
        val targetWidth = max(1, (image.width * scale).roundToInt())
        val targetHeight = max(1, (image.height * scale).roundToInt())
        var current = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        current.setRGB(0, 0, image.width, image.height, image.argb, 0, image.width)
        while (current.width / 2 >= targetWidth * 2 && current.height / 2 >= targetHeight * 2) {
            current = resized(current, current.width / 2, current.height / 2, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        }
        current = resized(current, targetWidth, targetHeight, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        return ColorImage(targetWidth, targetHeight, current.getRGB(0, 0, targetWidth, targetHeight, null, 0, targetWidth))
    }

    /** The picture turned by [degrees] (clockwise) about its middle, the same size; corners filled with [fill]. */
    fun rotated(image: ColorImage, degrees: Double, fill: Int): ColorImage {
        val source = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        source.setRGB(0, 0, image.width, image.height, image.argb, 0, image.width)
        val target = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val g = target.createGraphics()
        g.color = java.awt.Color(fill and 0xFFFFFF)
        g.fillRect(0, 0, image.width, image.height)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.rotate(Math.toRadians(degrees), image.width / 2.0, image.height / 2.0)
        g.drawImage(source, 0, 0, null)
        g.dispose()
        return ColorImage(image.width, image.height, target.getRGB(0, 0, image.width, image.height, null, 0, image.width))
    }

    private fun resized(image: BufferedImage, width: Int, height: Int, interpolation: Any): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(image, 0, 0, width, height, null)
        g.dispose()
        return out
    }

    private fun onWhite(argb: Int, alpha: Int): Int {
        fun blend(channel: Int) = (channel * alpha + 255 * (255 - alpha)) / 255
        val r = blend((argb shr 16) and 0xFF)
        val g = blend((argb shr 8) and 0xFF)
        val b = blend(argb and 0xFF)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
