package com.thelightphone.reader.comics

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Scan clean-up (CLAUDE.md 12): old scans are yellowed and flat. Each page's darkest and
 * brightest levels are measured once (per color channel, ignoring the darkest and brightest
 * 0.5% of its pixels) and cached; when the page is drawn, a color filter stretches them to
 * black and white and adds a little contrast. Nothing extra is kept in memory. Plain Kotlin,
 * unit-tested; the phone turns [cleanUpMatrix] into a Compose ColorFilter.
 */

/** This share of the pixels is ignored at each end when measuring a page's darkest and brightest levels. */
const val LEVELS_CLIP_SHARE = 0.005

/** A page's colored pixels (0xAARRGGBB, as Android and Java hand them out), row by row. */
class ColorImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0 && argb.size == width * height) { "Bad size $width x $height" }
    }

    operator fun get(x: Int, y: Int): Int = argb[y * width + x]

    fun gray(): GrayImage = grayFromArgb(argb, width, height)
}

/** The picture shrunk (each new pixel the average of the ones it covers, channel by channel) so its long side is [longSide]. */
fun shrinkColorToLongSide(image: ColorImage, longSide: Int = PANEL_GRID_LONG_SIDE): ColorImage {
    val scale = longSide.toDouble() / max(image.width, image.height)
    if (scale >= 1.0) return image
    val width = max(1, (image.width * scale).roundToInt())
    val height = max(1, (image.height * scale).roundToInt())
    val red = IntArray(width * height)
    val green = IntArray(width * height)
    val blue = IntArray(width * height)
    val counts = IntArray(width * height)
    for (y in 0 until image.height) {
        val row = (y.toLong() * height / image.height).toInt() * width
        for (x in 0 until image.width) {
            val i = row + (x.toLong() * width / image.width).toInt()
            val c = image[x, y]
            red[i] += (c shr 16) and 0xFF
            green[i] += (c shr 8) and 0xFF
            blue[i] += c and 0xFF
            counts[i]++
        }
    }
    val argb = IntArray(width * height) { i ->
        val n = max(1, counts[i])
        argbOf(red[i] / n, green[i] / n, blue[i] / n)
    }
    return ColorImage(width, height, argb)
}

/** The darkest and brightest level of each channel on a page (after ignoring [LEVELS_CLIP_SHARE] at each end). */
data class PageLevels(
    val blackRed: Int,
    val blackGreen: Int,
    val blackBlue: Int,
    val whiteRed: Int,
    val whiteGreen: Int,
    val whiteBlue: Int,
)

/** Measures [image]'s levels inside [area] (the whole picture by default). */
fun measureLevels(image: ColorImage, area: PixelRect = PixelRect(0, 0, image.width, image.height)): PageLevels {
    val red = IntArray(256)
    val green = IntArray(256)
    val blue = IntArray(256)
    for (y in area.top until area.bottom) {
        for (x in area.left until area.right) {
            val c = image[x, y]
            red[(c shr 16) and 0xFF]++
            green[(c shr 8) and 0xFF]++
            blue[c and 0xFF]++
        }
    }
    return PageLevels(
        blackRed = darkEnd(red), blackGreen = darkEnd(green), blackBlue = darkEnd(blue),
        whiteRed = lightEnd(red), whiteGreen = lightEnd(green), whiteBlue = lightEnd(blue),
    )
}

/** The level below which only [LEVELS_CLIP_SHARE] of the pixels lie. */
private fun darkEnd(counts: IntArray): Int {
    val skip = counts.sum() * LEVELS_CLIP_SHARE
    var seen = 0
    for (level in 0..255) {
        seen += counts[level]
        if (seen > skip) return level
    }
    return 0
}

/** The level above which only [LEVELS_CLIP_SHARE] of the pixels lie. */
private fun lightEnd(counts: IntArray): Int {
    val skip = counts.sum() * LEVELS_CLIP_SHARE
    var seen = 0
    for (level in 255 downTo 0) {
        seen += counts[level]
        if (seen > skip) return level
    }
    return 255
}

/**
 * How much a page is cleaned up when it's drawn (remembered per comic folder). [maxGain]: no
 * channel is stretched more than this (so a page with no white, like a red sky, keeps its
 * colors). [ownBlacks]: each channel gets its own black point (else they share the lowest, so
 * shadows keep their tint). [maxBlack]: the black point is never above this. [contrast]: the
 * boost around middle gray after stretching.
 */
enum class CleanUp(
    val label: String,
    val maxGain: Float,
    val ownBlacks: Boolean,
    val maxBlack: Int,
    val contrast: Float,
) {
    OFF("Off", 1f, false, 0, 1f),
    AUTO("Auto", 1.8f, false, 48, 1.08f),
    STRONG("Strong", 2.5f, true, 80, 1.2f);

    val previous: CleanUp? get() = entries.getOrNull(ordinal - 1)
    val next: CleanUp? get() = entries.getOrNull(ordinal + 1)

    companion object {
        val DEFAULT = AUTO
        fun fromSavedName(name: String?): CleanUp = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** One channel's change: new level = [gain] × old level + [offset] (levels 0 to 255). */
data class ChannelStretch(val gain: Float, val offset: Float) {
    fun apply(level: Int): Int = (gain * level + offset).roundToInt().coerceIn(0, 255)
}

/** The red, green and blue stretches for [levels] at [cleanUp]; [withContrast] false leaves out the contrast boost. */
fun channelStretches(levels: PageLevels, cleanUp: CleanUp, withContrast: Boolean = true): List<ChannelStretch> {
    val blacks = listOf(levels.blackRed, levels.blackGreen, levels.blackBlue)
    val whites = listOf(levels.whiteRed, levels.whiteGreen, levels.whiteBlue)
    val shared = blacks.min()
    val contrast = if (withContrast) cleanUp.contrast else 1f
    return blacks.indices.map { c ->
        val black = min(if (cleanUp.ownBlacks) blacks[c] else shared, cleanUp.maxBlack)
        val span = max(1, whites[c] - black)
        val gain = (255f / span).coerceIn(1f, cleanUp.maxGain)
        // Stretch (level - black) × gain, then contrast around middle gray.
        ChannelStretch(gain = gain * contrast, offset = -black * gain * contrast + MIDDLE_GRAY * (1 - contrast))
    }
}

private const val MIDDLE_GRAY = 128f

/**
 * The 4 × 5 color matrix (row by row, offsets in 0..255 levels, as Android's ColorMatrix) that
 * draws a page cleaned up; null when nothing changes (Off, or the page's levels aren't known).
 */
fun cleanUpMatrix(levels: PageLevels?, cleanUp: CleanUp): FloatArray? {
    if (levels == null || cleanUp == CleanUp.OFF) return null
    val (red, green, blue) = channelStretches(levels, cleanUp)
    return floatArrayOf(
        red.gain, 0f, 0f, 0f, red.offset,
        0f, green.gain, 0f, 0f, green.offset,
        0f, 0f, blue.gain, 0f, blue.offset,
        0f, 0f, 0f, 1f, 0f,
    )
}

/**
 * The page as gray levels for the panel detector: after the Strong stretch (without its
 * contrast), so yellowed paper comes out white and faint gutters stand out. Light, strongly
 * colored pixels are pulled down toward middle gray by how colored they are past
 * [PAPER_CHROMA], so bright yellow art is never taken for paper (leveled paper is close to
 * neutral). Dark colors are left alone: some comics' black gutters are a deep navy.
 */
fun leveledGray(image: ColorImage, levels: PageLevels): GrayImage {
    val (red, green, blue) = channelStretches(levels, CleanUp.STRONG, withContrast = false)
    val gray = ByteArray(image.width * image.height)
    for (i in gray.indices) {
        val c = image.argb[i]
        val r = red.apply((c shr 16) and 0xFF)
        val g = green.apply((c shr 8) and 0xFF)
        val b = blue.apply(c and 0xFF)
        val level = (r * 299 + g * 587 + b * 114) / 1000
        val pull = min(max(0, maxOf(r, g, b) - minOf(r, g, b) - PAPER_CHROMA), max(0, level - MIDDLE_LEVEL))
        gray[i] = (level - pull).toByte()
    }
    return GrayImage(image.width, image.height, gray)
}

/** Leveled paper differs this much at most between its brightest and dullest channel. */
private const val PAPER_CHROMA = 40
private const val MIDDLE_LEVEL = 128

/** Every channel drawn through its stretch (for the PC report's previews). */
fun cleanedUp(image: ColorImage, levels: PageLevels, cleanUp: CleanUp): ColorImage {
    if (cleanUp == CleanUp.OFF) return image
    val (red, green, blue) = channelStretches(levels, cleanUp)
    val argb = IntArray(image.argb.size) { i ->
        val c = image.argb[i]
        argbOf(red.apply((c shr 16) and 0xFF), green.apply((c shr 8) and 0xFF), blue.apply(c and 0xFF))
    }
    return ColorImage(image.width, image.height, argb)
}

fun argbOf(red: Int, green: Int, blue: Int): Int = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
