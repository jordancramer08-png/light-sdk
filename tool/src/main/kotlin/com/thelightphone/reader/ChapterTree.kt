package com.thelightphone.reader

import com.thelightphone.reader.data.ChapterMeta

/**
 * The Contents list with its headings (CLAUDE.md 9), in plain Kotlin so it can be tested.
 *
 * A book's contents can nest: "BOOK 1: GARDENS OF THE MOON" > "Book One: Pale" >
 * "Chapter One". Heading pages usually have almost no text, so the parser drops them as
 * chapters, but each chapter remembers the titles above it ([ChapterMeta.parents]). The
 * headings are rebuilt from those: a heading row appears where a chapter's parents first
 * differ from the chapter before it.
 */

/**
 * One row of the Contents list. [chapterIndex] is the chapter that tapping the row opens:
 * for a heading, the first readable chapter under it. A chapter with others nested under it
 * is a heading too.
 */
data class ContentsRow(val title: String, val depth: Int, val chapterIndex: Int, val isHeading: Boolean)

fun contentsRows(chapters: List<ChapterMeta>): List<ContentsRow> {
    val rows = mutableListOf<ContentsRow>()
    var openPath = emptyList<String>() // the previous chapter's parents, plus its own title
    chapters.forEachIndexed { i, chapter ->
        val shared = sharedPrefixLength(openPath, chapter.parents)
        for (level in shared until chapter.parents.size) {
            rows.add(ContentsRow(chapter.parents[level], level, chapter.index, isHeading = true))
        }
        val ownPath = chapter.parents + chapter.title
        val next = chapters.getOrNull(i + 1)
        val hasChildren = next != null && next.parents.size >= ownPath.size &&
            sharedPrefixLength(ownPath, next.parents) == ownPath.size
        rows.add(ContentsRow(chapter.title, chapter.parents.size, chapter.index, isHeading = hasChildren))
        openPath = ownPath
    }
    return rows
}

private fun sharedPrefixLength(a: List<String>, b: List<String>): Int {
    var n = 0
    while (n < a.size && n < b.size && a[n] == b[n]) n++
    return n
}

/**
 * The reader's top bar: "<top-level heading> · <chapter title>" when the book has
 * headings and this chapter is under one, otherwise just the chapter title.
 */
fun readerBarTitle(chapter: ChapterMeta): String {
    val topHeading = chapter.parents.firstOrNull() ?: return chapter.title
    return "$topHeading · ${chapter.title}"
}
