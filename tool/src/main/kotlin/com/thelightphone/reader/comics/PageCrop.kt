package com.thelightphone.reader.comics

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Auto-crop (CLAUDE.md 12): the blank border round a scanned page — white paper, black
 * scanner bed, or the gray shadow at the spine — is cut off before the page is shown and
 * before its panels are looked for. Plain Kotlin, unit-tested.
 *
 * From each side, lines (rows from the top and bottom, columns from the left and right) are
 * blank while almost all their pixels are the same gray as the line's middle gray. The first
 * line that isn't is where the content starts. A small safety margin is left, and never more
 * than [MAX_CROP_SHARE] of the page goes from any side.
 */

/** Never more than this share of the page's width (or height) is cut from one side. */
const val MAX_CROP_SHARE = 0.15

data class CropTuning(
    /** A line is blank when at least this share of its pixels is within [tolerance] of its middle gray. */
    val blankShare: Double = 0.97,
    val tolerance: Int = 24,
    /** A blank line's middle gray is paper (at least this light) ... */
    val minPaper: Int = 170,
    /** ... or scanner bed and shadow (at most this dark). Flat mid-gray art is never cut. */
    val maxShadow: Int = 120,
    /** Left round the content, as a share of the page's shorter side. */
    val safetyShare: Double = 0.01,
    val maxCropShare: Double = MAX_CROP_SHARE,
)

/** The part of [image] to keep, in its own pixels (the whole picture when it has no blank border). */
fun findCrop(image: GrayImage, tuning: CropTuning = CropTuning()): PixelRect {
    val maxX = floor(image.width * tuning.maxCropShare).toInt()
    val maxY = floor(image.height * tuning.maxCropShare).toInt()
    var left = 0
    var top = 0
    var right = image.width
    var bottom = image.height
    // Each side's last blank line's middle gray (-1: none yet), so a shadow's fade can be followed.
    val last = IntArray(4) { -1 }
    fun blank(side: Int, counts: IntArray, length: Int): Boolean {
        val middle = blankLineMiddle(counts, length, last[side], tuning) ?: return false
        last[side] = middle
        return true
    }
    // A shadow down one side stops rows from looking blank until that side is cut: go round again.
    repeat(3) {
        val before = PixelRect(left, top, right, bottom)
        while (top < maxY && blank(0, rowCounts(image, top, left, right), right - left)) top++
        while (bottom > image.height - maxY && blank(1, rowCounts(image, bottom - 1, left, right), right - left)) bottom--
        while (left < maxX && blank(2, columnCounts(image, left, top, bottom), bottom - top)) left++
        while (right > image.width - maxX && blank(3, columnCounts(image, right - 1, top, bottom), bottom - top)) right--
        if (PixelRect(left, top, right, bottom) == before) return withSafetyMargin(before, image, tuning)
    }
    return withSafetyMargin(PixelRect(left, top, right, bottom), image, tuning)
}

/** Gives back [CropTuning.safetyShare] of the page round each side that was cut. */
private fun withSafetyMargin(box: PixelRect, image: GrayImage, tuning: CropTuning): PixelRect {
    val safety = max(1, (minOf(image.width, image.height) * tuning.safetyShare).roundToInt())
    return PixelRect(
        left = if (box.left > 0) max(0, box.left - safety) else 0,
        top = if (box.top > 0) max(0, box.top - safety) else 0,
        right = if (box.right < image.width) minOf(image.width, box.right + safety) else image.width,
        bottom = if (box.bottom < image.height) minOf(image.height, box.bottom + safety) else image.height,
    )
}

/** How many pixels of each gray are in row [y], from [left] to [right]. */
private fun rowCounts(image: GrayImage, y: Int, left: Int, right: Int): IntArray {
    val counts = IntArray(256)
    for (x in left until right) counts[image[x, y]]++
    return counts
}

private fun columnCounts(image: GrayImage, x: Int, top: Int, bottom: Int): IntArray {
    val counts = IntArray(256)
    for (y in top until bottom) counts[image[x, y]]++
    return counts
}

/**
 * The line's middle gray when it's blank, else null. Blank: almost all of it within
 * [CropTuning.tolerance] of its middle gray, which is paper or shadow — or close to the
 * [previous] blank line's (a shadow fading into the paper). Flat mid-gray art is never blank.
 */
private fun blankLineMiddle(counts: IntArray, length: Int, previous: Int, tuning: CropTuning): Int? {
    if (length <= 0) return null
    var seen = 0
    var middle = 0
    while (middle < 255 && (seen + counts[middle]) * 2 <= length) seen += counts[middle++]
    val borderGray = middle <= tuning.maxShadow || middle >= tuning.minPaper
    val fading = previous >= 0 && abs(middle - previous) <= tuning.tolerance
    if (!borderGray && !fading) return null
    var near = 0
    for (level in max(0, middle - tuning.tolerance)..minOf(255, middle + tuning.tolerance)) near += counts[level]
    return middle.takeIf { near >= tuning.blankShare * length }
}

/** A crop found on a small picture, in the page's own pixels (rounded outward, so nothing more is cut). */
fun cropToPage(crop: PixelRect, image: GrayImage, pageWidth: Int, pageHeight: Int): PixelRect =
    rectToPage(crop, image.width, image.height, pageWidth, pageHeight)

/** [crop] ([PixelRect] in page pixels) kept within a page of [pageWidth] × [pageHeight]; the whole page when it's empty. */
fun cropWithinPage(crop: PixelRect?, pageWidth: Int, pageHeight: Int): PixelRect {
    val whole = PixelRect(0, 0, pageWidth, pageHeight)
    if (crop == null) return whole
    val kept = PixelRect(
        crop.left.coerceIn(0, pageWidth), crop.top.coerceIn(0, pageHeight),
        crop.right.coerceIn(0, pageWidth), crop.bottom.coerceIn(0, pageHeight),
    )
    return if (kept.isEmpty) whole else kept
}

/** [rect] moved into [crop]'s own pixels (its top-left corner becomes 0, 0), cut to fit inside it. */
fun PixelRect.within(crop: PixelRect): PixelRect = PixelRect(
    left = (left - crop.left).coerceIn(0, crop.width),
    top = (top - crop.top).coerceIn(0, crop.height),
    right = (right - crop.left).coerceIn(0, crop.width),
    bottom = (bottom - crop.top).coerceIn(0, crop.height),
)

/** [rect] moved by ([dx], [dy]). */
fun PixelRect.movedBy(dx: Int, dy: Int): PixelRect = PixelRect(left + dx, top + dy, right + dx, bottom + dy)
