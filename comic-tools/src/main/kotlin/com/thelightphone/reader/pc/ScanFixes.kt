package com.thelightphone.reader.pc

import com.thelightphone.reader.comics.ColorImage
import com.thelightphone.reader.comics.GrayImage
import com.thelightphone.reader.comics.argbOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Clean up scans on the PC (CLAUDE.md 12, "PC comic tool"): straighten a slightly tilted page,
 * even out yellowed or gray paper and uneven brightness, then a little more contrast. Each step
 * is careful: when it isn't sure (no clear tilt, no paper to measure), it leaves the page alone.
 * Plain Kotlin on pixel arrays; the turning itself is [PageImages.rotated].
 */
object ScanFixes {

    /** Tilts up to this many degrees either way are looked for. */
    const val MAX_SKEW_DEGREES = 2.0

    /** Tilts smaller than this aren't worth turning the page for. */
    const val MIN_SKEW_DEGREES = 0.2

    private const val SKEW_STEP_DEGREES = 0.1

    /** The best angle must line the ink up this much better than leaving it (5%), else there's no clear tilt. */
    private const val SKEW_GAIN = 1.05

    /** Ink, for finding the tilt: darker than this. */
    private const val INK_LEVEL = 110

    /**
     * How far the page's lines (panel borders, text rows) are tilted, in degrees: positive when
     * they go down to the right. 0 when there's no clear tilt. Turning the page by minus this
     * straightens it. [gray] is the page made small.
     */
    fun skewDegrees(gray: GrayImage): Double {
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        for (y in 0 until gray.height step 2) {
            for (x in 0 until gray.width step 2) {
                if (gray[x, y] < INK_LEVEL) {
                    xs += x
                    ys += y
                }
            }
        }
        if (xs.size < MIN_INK_POINTS) return 0.0
        val steps = (MAX_SKEW_DEGREES / SKEW_STEP_DEGREES).roundToInt()
        var best = 0.0
        var bestScore = 0.0
        var straightScore = 0.0
        for (i in -steps..steps) {
            val degrees = i * SKEW_STEP_DEGREES
            val score = alignment(xs, ys, gray.width, gray.height, degrees)
            if (i == 0) straightScore = score
            if (score > bestScore) {
                bestScore = score
                best = degrees
            }
        }
        if (abs(best) < MIN_SKEW_DEGREES || bestScore < straightScore * SKEW_GAIN) return 0.0
        return best
    }

    /**
     * How well the ink lines up in rows when the page is read at [degrees]: each ink point's row
     * after removing that tilt is counted, and the counts squared and summed (lined-up ink piles
     * into few rows, so it scores high).
     */
    private fun alignment(xs: List<Int>, ys: List<Int>, width: Int, height: Int, degrees: Double): Double {
        val slope = tan(Math.toRadians(degrees))
        val reach = (width * abs(slope)).toInt() + 1
        val rows = IntArray(height + 2 * reach)
        for (i in xs.indices) {
            val row = (ys[i] - xs[i] * slope).roundToInt() + reach
            if (row in rows.indices) rows[row]++
        }
        return rows.sumOf { it.toDouble() * it }
    }

    private const val MIN_INK_POINTS = 500

    // --- paper ------------------------------------------------------------------------

    /** The paper is evened out to this brightness. */
    private const val PAPER_TARGET = 245.0

    /** A patch counts as paper when its brightest pixels are at least this light ... */
    private const val MIN_PAPER = 140

    /** ... and not strongly colored (brightest minus dullest channel at most this). */
    private const val MAX_PAPER_CHROMA = 80

    /** At least this share of the page's patches must be paper, else it's left as it is (digital art, dark pages). */
    private const val MIN_PAPER_SHARE = 0.2

    /** Patches are this many pixels of the small picture, each way. */
    private const val PATCH = 25

    /** Brightening is held to this much at most, and a channel's color correction to these bounds. */
    private const val MAX_GAIN = 1.5
    private const val MIN_BALANCE = 0.85
    private const val MAX_BALANCE = 1.3

    /**
     * The page with its paper evened out: each part of the page brightened so its paper reaches
     * [PAPER_TARGET] (never darkened), and the paper's color cast (yellowing) taken out. [small]
     * is the same page made small, where the paper is measured. Null when the page has too
     * little paper to measure (then it's left alone).
     */
    fun evenPaper(image: ColorImage, small: ColorImage): ColorImage? {
        val field = paperField(small) ?: return null
        val out = IntArray(image.argb.size)
        val sx = field.columns.toDouble() / image.width
        val sy = field.rows.toDouble() / image.height
        for (y in 0 until image.height) {
            val fy = y * sy - 0.5
            for (x in 0 until image.width) {
                val paper = field.at(x * sx - 0.5, fy)
                val gain = (PAPER_TARGET / paper).coerceIn(1.0, MAX_GAIN)
                val c = image.argb[y * image.width + x]
                out[y * image.width + x] = argbOf(
                    channel((c shr 16) and 0xFF, gain * field.balanceRed),
                    channel((c shr 8) and 0xFF, gain * field.balanceGreen),
                    channel(c and 0xFF, gain * field.balanceBlue),
                )
            }
        }
        return ColorImage(image.width, image.height, out)
    }

    /** The paper's color (for filling a straightened page's corners): white when there's no paper to measure. */
    fun paperColor(small: ColorImage): Int {
        val field = paperField(small) ?: return argbOf(255, 255, 255)
        return argbOf(field.paperRed, field.paperGreen, field.paperBlue)
    }

    private fun channel(level: Int, gain: Double): Int = (level * gain).roundToInt().coerceIn(0, 255)

    /**
     * The paper's brightness patch by patch (smoothed, patches without paper taking the page's
     * paper), and how much each channel is scaled to make the paper gray.
     */
    private class PaperField(
        val columns: Int,
        val rows: Int,
        private val levels: DoubleArray,
        val paperRed: Int,
        val paperGreen: Int,
        val paperBlue: Int,
    ) {
        private val paperLevel = (paperRed + paperGreen + paperBlue) / 3.0
        val balanceRed = (paperLevel / max(1, paperRed)).coerceIn(MIN_BALANCE, MAX_BALANCE)
        val balanceGreen = (paperLevel / max(1, paperGreen)).coerceIn(MIN_BALANCE, MAX_BALANCE)
        val balanceBlue = (paperLevel / max(1, paperBlue)).coerceIn(MIN_BALANCE, MAX_BALANCE)

        /** The paper's brightness at patch position ([x], [y]), blended between patch centres. */
        fun at(x: Double, y: Double): Double {
            val cx = x.coerceIn(0.0, columns - 1.0)
            val cy = y.coerceIn(0.0, rows - 1.0)
            val x0 = cx.toInt()
            val y0 = cy.toInt()
            val x1 = min(x0 + 1, columns - 1)
            val y1 = min(y0 + 1, rows - 1)
            val tx = cx - x0
            val ty = cy - y0
            fun level(i: Int, j: Int) = levels[j * columns + i]
            val top = level(x0, y0) * (1 - tx) + level(x1, y0) * tx
            val bottom = level(x0, y1) * (1 - tx) + level(x1, y1) * tx
            return top * (1 - ty) + bottom * ty
        }
    }

    private fun paperField(small: ColorImage): PaperField? {
        val columns = max(1, small.width / PATCH)
        val rows = max(1, small.height / PATCH)
        val levels = DoubleArray(columns * rows)
        val isPaper = BooleanArray(columns * rows)
        val reds = ArrayList<Int>()
        val greens = ArrayList<Int>()
        val blues = ArrayList<Int>()
        for (j in 0 until rows) {
            for (i in 0 until columns) {
                val patch = brightestOf(small, i * small.width / columns, j * small.height / rows, (i + 1) * small.width / columns, (j + 1) * small.height / rows)
                val level = (patch.red + patch.green + patch.blue) / 3.0
                val chroma = max(patch.red, max(patch.green, patch.blue)) - min(patch.red, min(patch.green, patch.blue))
                levels[j * columns + i] = level
                if (level >= MIN_PAPER && chroma <= MAX_PAPER_CHROMA) {
                    isPaper[j * columns + i] = true
                    reds += patch.red
                    greens += patch.green
                    blues += patch.blue
                }
            }
        }
        if (reds.size < MIN_PAPER_SHARE * levels.size) return null
        val paperRed = median(reds)
        val paperGreen = median(greens)
        val paperBlue = median(blues)
        val paperLevel = (paperRed + paperGreen + paperBlue) / 3.0
        for (k in levels.indices) if (!isPaper[k]) levels[k] = paperLevel
        val smooth = smoothed(smoothed(levels, columns, rows), columns, rows)
        return PaperField(columns, rows, smooth, paperRed, paperGreen, paperBlue)
    }

    private class Patch(val red: Int, val green: Int, val blue: Int)

    /** The average color of the brightest tenth of the pixels in the box: the paper, if there's any. */
    private fun brightestOf(image: ColorImage, left: Int, top: Int, right: Int, bottom: Int): Patch {
        val counts = IntArray(256)
        for (y in top until bottom) for (x in left until right) counts[levelOf(image[x, y])]++
        val total = counts.sum()
        var seen = 0
        var cutoff = 255
        while (cutoff > 0 && seen + counts[cutoff] < max(1, total / 10)) {
            seen += counts[cutoff]
            cutoff--
        }
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (y in top until bottom) {
            for (x in left until right) {
                val c = image[x, y]
                if (levelOf(c) >= cutoff) {
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                    n++
                }
            }
        }
        if (n == 0) return Patch(0, 0, 0)
        return Patch((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun levelOf(c: Int) = (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3

    private fun median(values: List<Int>): Int = values.sorted()[values.size / 2]

    /** Each value the average of its 3 × 3 neighbourhood. */
    private fun smoothed(levels: DoubleArray, columns: Int, rows: Int): DoubleArray {
        val out = DoubleArray(levels.size)
        for (j in 0 until rows) {
            for (i in 0 until columns) {
                var sum = 0.0
                var n = 0
                for (dj in -1..1) for (di in -1..1) {
                    val x = i + di
                    val y = j + dj
                    if (x in 0 until columns && y in 0 until rows) {
                        sum += levels[y * columns + x]
                        n++
                    }
                }
                out[j * columns + i] = sum / n
            }
        }
        return out
    }

    // --- contrast ------------------------------------------------------------------------

    /** The darkest 0.5% of the page is taken as its black. */
    private const val BLACK_SHARE = 0.005

    /** Blacks lighter than this are deepened (scans often have gray blacks) ... */
    private const val MIN_BLACK_TO_FIX = 16

    /** ... by at most this much, so dark colors keep their shades. */
    private const val MAX_BLACK_SHIFT = 40

    /**
     * A little more contrast: a gray black (the darkest 0.5% of the page lighter than 16) is
     * moved toward black, by at most 40 levels, every level stretched evenly up to white. Null
     * when the blacks are already deep (nothing to do).
     */
    fun deepenBlacks(image: ColorImage): ColorImage? {
        val counts = IntArray(256)
        for (i in image.argb.indices step 3) counts[levelOf(image.argb[i])]++
        val total = counts.sum()
        var seen = 0
        var black = 0
        while (black < 255 && seen + counts[black] < total * BLACK_SHARE) {
            seen += counts[black]
            black++
        }
        if (black < MIN_BLACK_TO_FIX) return null
        val shift = min(black, MAX_BLACK_SHIFT)
        val gain = 255.0 / (255 - shift)
        val table = IntArray(256) { ((it - shift) * gain).roundToInt().coerceIn(0, 255) }
        val out = IntArray(image.argb.size) { i ->
            val c = image.argb[i]
            argbOf(table[(c shr 16) and 0xFF], table[(c shr 8) and 0xFF], table[c and 0xFF])
        }
        return ColorImage(image.width, image.height, out)
    }
}
