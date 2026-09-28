package com.thelightphone.reader

import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.ReadingPosition

/**
 * Reading-list logic in plain Kotlin, so it can be unit-tested on the PC. No Android here.
 */

/** What the Library is showing: every book, or just one list's. */
sealed interface LibraryView {
    data object AllBooks : LibraryView
    data class OneList(val listId: Long) : LibraryView
}

/**
 * The rows for one list, in the list's own order. A slug whose book is no longer on the
 * phone is skipped (its entry is kept, so the book comes back if it's sent again).
 */
fun listRows(
    books: List<BookMeta>,
    positions: Map<String, ReadingPosition>,
    listSlugs: List<String>,
): List<LibraryRow> {
    val booksBySlug = books.associateBy { it.slug }
    return listSlugs.mapNotNull { slug ->
        booksBySlug[slug]?.let { LibraryRow(it, statusText(it, positions[it.slug])) }
    }
}

/**
 * The book a move trades places with: the one just above ([up]) or just below [slug] in
 * [shownSlugs], or null at either end. Uses the books as shown, so hidden entries (books
 * no longer on the phone) never swallow a move.
 */
fun neighbourSlug(shownSlugs: List<String>, slug: String, up: Boolean): String? {
    val index = shownSlugs.indexOf(slug)
    if (index < 0) return null
    return shownSlugs.getOrNull(if (up) index - 1 else index + 1)
}

/** A typed list name with spaces tidied; null when nothing usable was typed. */
fun cleanListName(typed: String?): String? =
    typed?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
