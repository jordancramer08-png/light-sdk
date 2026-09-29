package com.thelightphone.reader.comics

/**
 * Comic and folder names, in plain Kotlin so they can be unit-tested on the PC (CLAUDE.md 12).
 * No Android here.
 */

/** Extensions dropped from a name before it's shown. Folders keep dots ("Vol. 2"). */
private val SHOWN_EXTENSIONS = listOf(".cbz", ".cbr", ".zip", ".txt")

/**
 * The reading-order number at the start of a name, and the separator after it:
 * "00001. ", "00102a. ", "01 - ", "7) ", "12_". A number with no separator after it
 * ("2000 AD", "1984") is part of the title and stays.
 */
private val LEADING_NUMBER = Regex("""^\d+[A-Za-z]?\s*[.\-_)]\s*""")

/**
 * The title shown for a comic, note or folder, from its file name:
 * "00001. Action Comics #1 (1938).cbz" -> "Action Comics #1 (1938)",
 * "01. Book I - Earth-One (1938-1985)" -> "Book I - Earth-One (1938-1985)".
 * A name that is only a number keeps it.
 */
fun cleanComicTitle(fileName: String): String {
    val extension = SHOWN_EXTENSIONS.firstOrNull { fileName.endsWith(it, ignoreCase = true) }
    val base = if (extension != null) fileName.dropLast(extension.length) else fileName
    val cleaned = base.replaceFirst(LEADING_NUMBER, "").trim()
    return cleaned.ifEmpty { base.trim() }
}

/**
 * File-name order where numbers count as numbers: "Page 2" before "Page 10", and "00102."
 * before "00102a." (a note filed after its comic). Letters ignore case.
 */
val NaturalOrder: Comparator<String> = Comparator { a, b -> naturalCompare(a, b) }

fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
            val endA = digitsEnd(a, i)
            val endB = digitsEnd(b, j)
            val byNumber = compareNumbers(a.substring(i, endA), b.substring(j, endB))
            if (byNumber != 0) return byNumber
            i = endA
            j = endB
        } else {
            val byLetter = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
            if (byLetter != 0) return byLetter
            i++
            j++
        }
    }
    val byLength = (a.length - i).compareTo(b.length - j)
    return if (byLength != 0) byLength else a.compareTo(b)
}

private fun Char.isAsciiDigit() = this in '0'..'9'

private fun digitsEnd(text: String, start: Int): Int {
    var end = start
    while (end < text.length && text[end].isAsciiDigit()) end++
    return end
}

/** Two runs of digits by value, however long ("007" = "7"). */
private fun compareNumbers(a: String, b: String): Int {
    val x = a.trimStart('0')
    val y = b.trimStart('0')
    return if (x.length != y.length) x.length.compareTo(y.length) else x.compareTo(y)
}

/** Comics are known by "comic:" + their path in the comics folder, so they never clash with a book's slug. */
private const val COMIC_SLUG_PREFIX = "comic:"

/** The key a comic is saved under (status, lists, place): "comic:DC Comics/…/00001. Action Comics #1 (1938).cbz". */
fun comicSlug(path: String): String = COMIC_SLUG_PREFIX + path

/** The comic's path back from its key; null for a book's slug. */
fun comicPathOf(slug: String): String? =
    if (slug.startsWith(COMIC_SLUG_PREFIX)) slug.removePrefix(COMIC_SLUG_PREFIX) else null
