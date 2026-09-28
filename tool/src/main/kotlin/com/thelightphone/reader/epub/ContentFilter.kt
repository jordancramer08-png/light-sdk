package com.thelightphone.reader.epub

// EPUBs commonly include spine items that aren't reading content: a cover
// image page, a title/half-title page repeating the book's own title, and —
// for books that circulated through certain distribution sites — an ad slug
// for that site. All three share one trait: almost no extracted text. A real
// chapter, even a short one, still runs to hundreds of characters.
const val MIN_CONTENT_CHARS = 300

// Titles publishers commonly give front/back-matter items, matched exactly
// (after trimming whitespace and a trailing colon) so a real chapter that
// merely contains one of these words — e.g. "Dedication Day" — is untouched.
private val NON_CONTENT_TITLES = setOf(
    "cover", "cover page", "title page", "half title page",
    "copyright", "copyright page", "dedication", "epigraph", "quotes", "map",
    "table of contents", "about the author", "about the publisher",
    "illustration credits", "notes and sources", "information about the series",
)

// A spine item whose only title is a bare domain name is a distributor/ad
// slug, not a chapter.
private val AD_SLUG_RE = Regex("^[\\w-]+\\.(com|net|org|info)$", RegexOption.IGNORE_CASE)

// Some books bake a human-readable table of contents (or back-of-book index)
// into the spine as an ordinary content item, distinct from the
// machine-readable EPUB nav document (already excluded via its manifest
// "nav" property). It has no NCX title of its own, so it falls back to
// "Chapter N" and can run past MIN_CONTENT_CHARS, but it reads as dozens of
// one-line entries rather than prose, so its average paragraph length is tiny.
private const val MIN_LISTING_PARAGRAPHS = 5
private const val MAX_LISTING_AVG_PARAGRAPH_CHARS = 40

private fun looksLikeAListing(text: String): Boolean {
    val paragraphs = text.split("\n\n").filter { it.isNotEmpty() }
    if (paragraphs.size < MIN_LISTING_PARAGRAPHS) return false
    val avgLength = paragraphs.sumOf { it.length }.toDouble() / paragraphs.size
    return avgLength < MAX_LISTING_AVG_PARAGRAPH_CHARS
}

// A page of pull quotes from reviews ("PRAISE FOR ..."), also untitled in the
// NCX and long enough to clear MIN_CONTENT_CHARS, since each quote is a full
// sentence — the listing check's short-paragraph heuristic doesn't catch it.
private val PRAISE_PAGE_RE = Regex("^(advance )?praise for\\b", RegexOption.IGNORE_CASE)

private fun looksLikeAPraisePage(text: String): Boolean {
    val paragraphs = text.split("\n\n").filter { it.isNotEmpty() }.take(3)
    return paragraphs.any { PRAISE_PAGE_RE.containsMatchIn(it) }
}

fun isNonContent(title: String?, text: String): Boolean {
    if (text.length < MIN_CONTENT_CHARS) return true
    if (looksLikeAListing(text) || looksLikeAPraisePage(text)) return true
    if (title == null) return false
    val normalized = title.trim().trimEnd(':').trim().lowercase()
    if (normalized in NON_CONTENT_TITLES || normalized.startsWith("also by ")) return true
    return AD_SLUG_RE.matches(title.trim())
}
