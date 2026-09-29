package com.thelightphone.reader.comics

import kotlin.math.cos
import kotlin.math.sin

/** A test page drawn in gray levels: [background] everywhere, then panels, balloons and borders on top. */
internal class SyntheticPage(val width: Int, val height: Int, background: Int) {
    private val levels = IntArray(width * height) { background }

    operator fun get(x: Int, y: Int): Int = levels[y * width + x]

    fun set(x: Int, y: Int, level: Int) {
        if (x in 0 until width && y in 0 until height) levels[y * width + x] = level
    }

    fun fill(rect: PixelRect, level: Int) {
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) set(x, y, level)
    }

    /** A panel: a black border 2 px wide round art in stripes of [art] and a little lighter. */
    fun panel(rect: PixelRect, art: Int = 90) {
        for (y in rect.top until rect.bottom) {
            for (x in rect.left until rect.right) set(x, y, panelLevel(x - rect.left, y - rect.top, rect.width, rect.height, art))
        }
    }

    /** [panel], turned [degrees] (clockwise) about the point ([cx], [cy]). */
    fun tiltedPanel(rect: PixelRect, degrees: Double, cx: Double, cy: Double, art: Int = 90) {
        val angle = Math.toRadians(degrees)
        val c = cos(angle)
        val s = sin(angle)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Turn the pixel back to see where it falls on the untilted panel.
                val dx = x + 0.5 - cx
                val dy = y + 0.5 - cy
                val ux = (cx + dx * c + dy * s).toInt()
                val uy = (cy - dx * s + dy * c).toInt()
                if (ux in rect.left until rect.right && uy in rect.top until rect.bottom) {
                    set(x, y, panelLevel(ux - rect.left, uy - rect.top, rect.width, rect.height, art))
                }
            }
        }
    }

    /** A speech balloon: an ellipse filled white with a black outline 2 px wide. */
    fun balloon(cx: Int, cy: Int, rx: Int, ry: Int) {
        for (y in cy - ry..cy + ry) {
            for (x in cx - rx..cx + rx) {
                val outer = sq(x - cx, rx) + sq(y - cy, ry)
                val inner = sq(x - cx, rx - 2) + sq(y - cy, ry - 2)
                if (outer <= 1.0) set(x, y, if (inner <= 1.0) 255 else 0)
            }
        }
    }

    private fun sq(d: Int, r: Int): Double = d.toDouble() * d / (r.toDouble() * r)

    private fun panelLevel(x: Int, y: Int, w: Int, h: Int, art: Int): Int =
        if (x < 2 || y < 2 || x >= w - 2 || y >= h - 2) 0 else if ((x + y) % 7 == 0) art + 30 else art

    fun gray(): GrayImage = GrayImage(width, height, ByteArray(levels.size) { levels[it].toByte() })

    /** The page in color: each gray level [tint]ed (white paper becomes the tint's white). */
    fun color(tint: (Int) -> Int = { argbOf(it, it, it) }): ColorImage =
        ColorImage(width, height, IntArray(levels.size) { tint(levels[it]) })
}

internal fun rect(left: Int, top: Int, right: Int, bottom: Int) = PixelRect(left, top, right, bottom)

/** Old yellowed paper: white comes out as (235, 220, 170), black as (20, 20, 20). */
internal fun yellowed(level: Int): Int = argbOf(20 + level * 215 / 255, 20 + level * 200 / 255, 20 + level * 150 / 255)
