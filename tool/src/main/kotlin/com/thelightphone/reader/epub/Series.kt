package com.thelightphone.reader.epub

/** A book's series and its place in it: ("Cemetery of Forgotten Books", "1"). The number may be missing. */
data class Series(val name: String, val number: String?)

private const val CALIBRE_SERIES = "calibre:series"
private const val CALIBRE_SERIES_INDEX = "calibre:series_index"
private const val EPUB3_COLLECTION = "belongs-to-collection"
private const val EPUB3_POSITION = "group-position"

/**
 * The series named in the OPF metadata, or null. Two common ways of writing it:
 *
 * - Calibre: `<meta name="calibre:series" content="…"/>` and
 *   `<meta name="calibre:series_index" content="1.0"/>`
 * - EPUB 3: `<meta property="belongs-to-collection" id="c1">…</meta>` and
 *   `<meta refines="#c1" property="group-position">1</meta>`
 *
 * Calibre's wins when both are there. Reads the raw tokens rather than the element tree,
 * because the tree treats `<meta>` as an empty tag and loses the EPUB 3 text inside it.
 */
fun readSeries(opfText: String): Series? {
    var calibreName: String? = null
    var calibreNumber: String? = null
    val collectionNames = LinkedHashMap<String, String>() // id -> name, in order
    val collectionNumbers = mutableMapOf<String, String>() // id -> number

    val tokens = tokenizeMarkup(opfText)
    tokens.forEachIndexed { i, token ->
        if (token !is MarkupToken.StartTag || token.name != "meta") return@forEachIndexed
        val attrs = token.attrs
        val text = (tokens.getOrNull(i + 1) as? MarkupToken.Text)?.text?.trim().orEmpty()
        when {
            attrs["name"] == CALIBRE_SERIES -> calibreName = attrs["content"]?.trim()
            attrs["name"] == CALIBRE_SERIES_INDEX -> calibreNumber = attrs["content"]?.trim()
            attrs["property"] == EPUB3_COLLECTION && text.isNotEmpty() ->
                collectionNames[attrs["id"].orEmpty()] = text
            attrs["property"] == EPUB3_POSITION && text.isNotEmpty() ->
                collectionNumbers[attrs["refines"].orEmpty().removePrefix("#")] = text
        }
    }

    calibreName?.takeIf { it.isNotEmpty() }?.let { return Series(it, tidySeriesNumber(calibreNumber)) }
    val (id, name) = collectionNames.entries.firstOrNull() ?: return null
    return Series(name, tidySeriesNumber(collectionNumbers[id]))
}

/** "Series NN. Title - Author.epub", the way Jordan's library files are named. */
private val SERIES_FILE_NAME = Regex("""^(.+?) (\d{1,3}(?:\.\d+)?)\. .+ - .+\.epub$""", RegexOption.IGNORE_CASE)

/**
 * The series from a file name like `Cemetery of Forgotten Books 01. The Shadow of the
 * Wind - Carlos Ruiz Zafon.epub`, for books whose metadata doesn't say. Null for a
 * standalone (`Title - Author.epub`).
 */
fun seriesFromFileName(fileName: String): Series? {
    val match = SERIES_FILE_NAME.matchEntire(fileName) ?: return null
    return Series(match.groupValues[1].trim(), tidySeriesNumber(match.groupValues[2]))
}

/** "01" -> "1", "1.0" -> "1", "2.50" -> "2.5". Anything that isn't a number is kept as written. */
fun tidySeriesNumber(number: String?): String? {
    val trimmed = number?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val decimal = trimmed.toBigDecimalOrNull() ?: return trimmed
    return decimal.stripTrailingZeros().toPlainString()
}
