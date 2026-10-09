package com.thelightphone.reader.pc

import com.thelightphone.reader.comics.PageLevels
import com.thelightphone.reader.comics.PixelRect

/**
 * The panels file sent beside a comic (`<comic name>.panels.json`, CLAUDE.md 12): what was
 * found on each page, so the phone never has to look. Read on the phone by `ComicPanelsSidecar`.
 * [fileSize] is the size of the CBZ it belongs to: a file that doesn't match is ignored.
 */
class PanelsFile(
    val fileSize: Long,
    val cropped: Boolean,
    val cleaned: Boolean,
    val pages: Map<String, PageEntry>,
) {
    /** One page: its size, its panels left to right (none = shown whole), its crop and levels (null = none / not used). */
    class PageEntry(
        val pageWidth: Int,
        val pageHeight: Int,
        val panels: List<PixelRect>,
        val crop: PixelRect?,
        val levels: PageLevels?,
    )

    fun toJson(): String = buildString {
        append("{\"version\":").append(FORMAT_VERSION)
        append(",\"tool\":").append(TOOL_VERSION)
        append(",\"fileSize\":").append(fileSize)
        append(",\"cropped\":").append(cropped)
        append(",\"cleaned\":").append(cleaned)
        append(",\"pages\":{")
        pages.entries.forEachIndexed { i, (name, page) ->
            if (i > 0) append(',')
            append('\n').append(quoted(name)).append(':')
            append("{\"pageWidth\":").append(page.pageWidth)
            append(",\"pageHeight\":").append(page.pageHeight)
            append(",\"panels\":[")
            page.panels.forEachIndexed { j, rect ->
                if (j > 0) append(',')
                append(box(rect))
            }
            append(']')
            append(",\"crop\":").append(page.crop?.let(::box) ?: "null")
            append(",\"levels\":").append(page.levels?.let(::levels) ?: "null")
            append('}')
        }
        append("\n}}\n")
    }

    companion object {
        /** The file's layout. The phone ignores a file of another version. */
        const val FORMAT_VERSION = 1

        /**
         * Bump when what the tool does to pages, or how it finds panels, changes: the send
         * script's cache keys include it, so every comic is looked at again.
         */
        const val TOOL_VERSION = 1

        private fun box(r: PixelRect) = "{\"left\":${r.left},\"top\":${r.top},\"right\":${r.right},\"bottom\":${r.bottom}}"

        private fun levels(l: PageLevels) =
            "{\"black\":[${l.blackRed},${l.blackGreen},${l.blackBlue}],\"white\":[${l.whiteRed},${l.whiteGreen},${l.whiteBlue}]}"

        fun quoted(s: String): String = buildString {
            append('"')
            for (c in s) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }

        /** A panels file written before, given the size of the CBZ it now belongs to (only that number changes). */
        fun withFileSize(json: String, fileSize: Long): String? {
            val pattern = Regex("\"fileSize\":\\d+")
            if (!pattern.containsMatchIn(json)) return null
            return pattern.replaceFirst(json, "\"fileSize\":$fileSize")
        }
    }
}
