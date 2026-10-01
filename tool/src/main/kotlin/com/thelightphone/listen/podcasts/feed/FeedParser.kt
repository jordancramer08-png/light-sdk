package com.thelightphone.listen.podcasts.feed

import com.thelightphone.listen.podcasts.PodcastIds
import com.thelightphone.listen.podcasts.chapters.PscChapter
import com.thelightphone.listen.podcasts.xml.HtmlToText
import com.thelightphone.listen.podcasts.xml.MarkupReader
import com.thelightphone.listen.podcasts.xml.MarkupToken
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.nio.charset.Charset

/**
 * Reads an RSS podcast feed, one token at a time (a big feed never sits in memory whole).
 * Treats the feed as hostile: see [MarkupReader] for the XML hardening; on top of that,
 * every field has a size cap, nesting depth is capped, and odd or missing values fall back
 * instead of failing (missing guid → enclosure address; unreadable date → null; no
 * `itunes:image` → `<image><url>`). Items without an audio enclosure aren't episodes and are
 * skipped. Namespaces are matched by their declared address, so a feed using an unusual
 * prefix still works.
 */
object FeedParser {

    /** Most episodes kept from one feed. */
    const val MAX_EPISODES = 10_000

    /** Longest description kept, in characters, before conversion to text. */
    const val MAX_NOTES_CHARS = 200_000

    private const val MAX_FIELD_CHARS = 4_000
    private const val MAX_DEPTH = 128

    /** Reads a feed from bytes, using the encoding its XML declaration names (UTF-8 by default). */
    fun parse(input: InputStream, feedUrl: String?): ParsedFeed = parse(feedReader(input), feedUrl)

    fun parse(text: String, feedUrl: String?): ParsedFeed = parse(StringReader(text.removePrefix("﻿")), feedUrl)

    fun parse(reader: Reader, feedUrl: String?): ParsedFeed = Parse(feedUrl).run(MarkupReader(reader, html = false))

    /** A reader for feed bytes: skips a byte-order mark and honours `<?xml encoding="…"?>`. */
    fun feedReader(input: InputStream): Reader {
        val buffered = BufferedInputStream(input, 64 * 1024)
        buffered.mark(1024)
        val head = ByteArray(1024)
        var n = 0
        while (n < head.size) {
            val r = buffered.read(head, n, head.size - n)
            if (r < 0) break
            n += r
        }
        buffered.reset()
        var charset: Charset = Charsets.UTF_8
        var skip = 0
        when {
            n >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte() -> skip = 3
            n >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte() -> { charset = Charsets.UTF_16BE; skip = 2 }
            n >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte() -> { charset = Charsets.UTF_16LE; skip = 2 }
            else -> {
                val declared = ENCODING_RE.find(String(head, 0, n, Charsets.ISO_8859_1))?.groupValues?.get(1)
                if (declared != null) {
                    charset = try {
                        Charset.forName(declared)
                    } catch (e: Exception) {
                        Charsets.UTF_8
                    }
                }
            }
        }
        repeat(skip) { buffered.read() }
        return InputStreamReader(buffered, charset)
    }

    private val ENCODING_RE = Regex("""<\?xml[^>]*encoding\s*=\s*["']([A-Za-z0-9._-]{1,40})["']""")

    /** The namespaces a podcast feed uses, identified by their address. */
    private enum class Ns { RSS, ITUNES, CONTENT, PODCAST, PSC, DC, OTHER }

    private fun nsForUri(uri: String?): Ns {
        if (uri == null) return Ns.RSS
        val u = uri.lowercase()
        return when {
            "itunes.com/dtds" in u -> Ns.ITUNES
            "purl.org/rss/1.0/modules/content" in u -> Ns.CONTENT
            "podcastindex" in u || "podcast-namespace" in u -> Ns.PODCAST
            "podlove.org/simple-chapters" in u -> Ns.PSC
            "purl.org/dc/elements" in u -> Ns.DC
            "userland.com" in u || "purl.org/rss/1.0" in u -> Ns.RSS
            else -> Ns.OTHER
        }
    }

    /** A prefix the feed uses but never declared: guess from the usual names. */
    private fun nsForPrefix(prefix: String): Ns = when (prefix) {
        "itunes" -> Ns.ITUNES
        "content" -> Ns.CONTENT
        "podcast" -> Ns.PODCAST
        "psc" -> Ns.PSC
        "dc" -> Ns.DC
        else -> Ns.OTHER
    }

    private class Element(val rawName: String, val ns: Ns, val local: String, val declarations: Map<String, String>?)

    /** What a captured element's text is for. */
    private enum class Field(val maxChars: Int) {
        SHOW_TITLE(MAX_FIELD_CHARS), SHOW_AUTHOR(MAX_FIELD_CHARS), SHOW_OWNER_NAME(MAX_FIELD_CHARS),
        SHOW_EDITOR(MAX_FIELD_CHARS), SHOW_DESCRIPTION(MAX_NOTES_CHARS), SHOW_SUMMARY(MAX_NOTES_CHARS),
        SHOW_IMAGE_URL(MAX_FIELD_CHARS), SHOW_LINK(MAX_FIELD_CHARS), SHOW_NEW_FEED_URL(MAX_FIELD_CHARS),
        ITEM_TITLE(MAX_FIELD_CHARS), ITEM_ITUNES_TITLE(MAX_FIELD_CHARS), ITEM_GUID(MAX_FIELD_CHARS),
        ITEM_DATE(MAX_FIELD_CHARS), ITEM_DC_DATE(MAX_FIELD_CHARS), ITEM_DURATION(MAX_FIELD_CHARS),
        ITEM_DESCRIPTION(MAX_NOTES_CHARS), ITEM_CONTENT(MAX_NOTES_CHARS), ITEM_SUMMARY(MAX_NOTES_CHARS),
    }

    private class Capture(val field: Field, val depth: Int) {
        val text = StringBuilder()
        fun append(s: String) {
            val room = field.maxChars - text.length
            if (room > 0) text.append(if (s.length <= room) s else s.substring(0, room))
        }
    }

    /** One item being read. */
    private class ItemBuilder {
        val fields = HashMap<Field, String>()
        var enclosureUrl: String? = null
        var enclosureType: String? = null
        var enclosureBytes: Long? = null
        var imageUrl: String? = null
        var chaptersUrl: String? = null
        var chaptersType: String? = null
        val psc = mutableListOf<PscChapter>()
        val transcripts = mutableListOf<TranscriptLink>()
    }

    private class Parse(private val feedUrl: String?) {
        private val stack = ArrayList<Element>()
        private var ignoredDepth = 0
        private var capture: Capture? = null
        private val show = HashMap<Field, String>()
        private var showImageHref: String? = null
        private var item: ItemBuilder? = null
        private var itemDepth = -1
        private var sawRss = false
        private val episodes = ArrayList<Episode>()
        private val notes = HashMap<String, String>()
        private val seenIds = HashSet<String>()

        fun run(reader: MarkupReader): ParsedFeed {
            while (true) {
                val token = reader.next() ?: break
                when (token) {
                    is MarkupToken.StartTag -> start(token)
                    is MarkupToken.EndTag -> end(token.name)
                    is MarkupToken.Text -> capture?.append(token.text)
                }
            }
            if (!sawRss) throw NotAFeedException("This address isn't a podcast feed.")
            val title = HtmlToText.inline(show[Field.SHOW_TITLE]).ifEmpty { "Untitled podcast" }
            val author = listOf(Field.SHOW_AUTHOR, Field.SHOW_OWNER_NAME, Field.SHOW_EDITOR)
                .map { HtmlToText.inline(show[it]) }.firstOrNull { it.isNotEmpty() }.orEmpty()
            val art = PodcastIds.resolve(feedUrl, showImageHref) ?: PodcastIds.resolve(feedUrl, show[Field.SHOW_IMAGE_URL]?.trim())
            val summary = HtmlToText.convert(show[Field.SHOW_SUMMARY])
            val description = HtmlToText.convert(show[Field.SHOW_DESCRIPTION])
            return ParsedFeed(
                show = ShowInfo(
                    title = title,
                    author = author,
                    artUrl = art,
                    link = PodcastIds.resolve(feedUrl, show[Field.SHOW_LINK]?.trim()),
                    newFeedUrl = PodcastIds.resolve(feedUrl, show[Field.SHOW_NEW_FEED_URL]?.trim()),
                ),
                showNotes = if (summary.length > description.length) summary else description,
                episodes = episodes,
                episodeNotes = notes,
            )
        }

        private fun start(tag: MarkupToken.StartTag) {
            capture?.let { c ->
                // Unescaped HTML inside a description: keep it as markup for HtmlToText.
                c.append(renderTag(tag))
                if (!tag.selfClosing) {
                    if (stack.size >= MAX_DEPTH) ignoredDepth++ else push(tag)
                }
                return
            }
            if (stack.size >= MAX_DEPTH) {
                if (!tag.selfClosing) ignoredDepth++
                return
            }
            val parent = stack.lastOrNull()
            val element = push(tag) ?: return
            if (element.local == "rss" || element.local == "channel" || element.local == "rdf") sawRss = true
            handle(element, parent, tag)
            if (tag.selfClosing) pop()
        }

        /** Pushes a (non-self-closing) element, resolving its namespace. Returns it. */
        private fun push(tag: MarkupToken.StartTag): Element? {
            var declarations: HashMap<String, String>? = null
            for ((k, v) in tag.attrs) {
                if (k == "xmlns") (declarations ?: HashMap<String, String>().also { declarations = it })[""] = v
                else if (k.startsWith("xmlns:")) (declarations ?: HashMap<String, String>().also { declarations = it })[k.substring(6)] = v
            }
            val colon = tag.name.indexOf(':')
            val prefix = if (colon < 0) "" else tag.name.substring(0, colon)
            val local = if (colon < 0) tag.name else tag.name.substring(colon + 1)
            val uri = declarations?.get(prefix) ?: lookup(prefix)
            val ns = when {
                uri != null -> nsForUri(uri)
                prefix.isEmpty() -> Ns.RSS
                else -> nsForPrefix(prefix)
            }
            val element = Element(tag.name, ns, local, declarations)
            if (!tag.selfClosing || capture == null) stack.add(element)
            return element
        }

        private fun lookup(prefix: String): String? {
            for (i in stack.indices.reversed()) stack[i].declarations?.get(prefix)?.let { return it }
            return null
        }

        private fun pop() {
            if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
        }

        private fun end(name: String) {
            if (ignoredDepth > 0) {
                ignoredDepth--
                return
            }
            val index = stack.indexOfLast { it.rawName == name }
            if (index < 0) return // a stray end tag
            capture?.let { c ->
                if (index + 1 > c.depth) {
                    c.append("</$name>")
                    while (stack.size > index) pop()
                    return
                }
                finishCapture(c)
                capture = null
            }
            while (stack.size > index) {
                val closing = stack.size - 1 == itemDepth
                pop()
                if (closing) finishItem()
            }
        }

        private fun handle(e: Element, parent: Element?, tag: MarkupToken.StartTag) {
            val inItem = item != null
            val parentLocal = parent?.local
            // An item: a child of channel (RSS 2.0) or of the root (RSS 1.0).
            if (e.ns == Ns.RSS && e.local == "item" && !inItem) {
                if (!tag.selfClosing) {
                    item = ItemBuilder()
                    itemDepth = stack.size - 1
                }
                return
            }
            if (inItem) {
                val directChild = stack.size - 2 == itemDepth
                if (directChild) handleItemChild(e, tag) else if (e.ns == Ns.PSC && e.local == "chapter" && parent?.ns == Ns.PSC) {
                    val start = tag.attrs["start"] ?: return
                    if (item!!.psc.size < 1000) item!!.psc += PscChapter(start, tag.attrs["title"].orEmpty(), tag.attrs["href"])
                }
                return
            }
            when {
                parentLocal == "channel" && parent?.ns == Ns.RSS -> handleChannelChild(e, tag)
                parentLocal == "image" && parent?.ns == Ns.RSS && e.ns == Ns.RSS && e.local == "url" ->
                    begin(Field.SHOW_IMAGE_URL, tag)
                parentLocal == "owner" && parent?.ns == Ns.ITUNES && e.ns == Ns.ITUNES && e.local == "name" ->
                    begin(Field.SHOW_OWNER_NAME, tag)
            }
        }

        private fun handleChannelChild(e: Element, tag: MarkupToken.StartTag) {
            when (e.ns) {
                Ns.RSS -> when (e.local) {
                    "title" -> begin(Field.SHOW_TITLE, tag)
                    "description" -> begin(Field.SHOW_DESCRIPTION, tag)
                    "link" -> begin(Field.SHOW_LINK, tag)
                    "managingeditor" -> begin(Field.SHOW_EDITOR, tag)
                }
                Ns.ITUNES -> when (e.local) {
                    "author" -> begin(Field.SHOW_AUTHOR, tag)
                    "summary" -> begin(Field.SHOW_SUMMARY, tag)
                    "image" -> if (showImageHref == null) showImageHref = tag.attrs["href"]?.trim()?.takeIf { it.isNotEmpty() }
                    "new-feed-url" -> begin(Field.SHOW_NEW_FEED_URL, tag)
                }
                else -> Unit
            }
        }

        private fun handleItemChild(e: Element, tag: MarkupToken.StartTag) {
            val it = item ?: return
            when (e.ns) {
                Ns.RSS -> when (e.local) {
                    "title" -> begin(Field.ITEM_TITLE, tag)
                    "guid" -> begin(Field.ITEM_GUID, tag)
                    "pubdate" -> begin(Field.ITEM_DATE, tag)
                    "description" -> begin(Field.ITEM_DESCRIPTION, tag)
                    "enclosure" -> if (it.enclosureUrl == null || !isAudio(it.enclosureType)) {
                        val url = PodcastIds.resolve(feedUrl, tag.attrs["url"])
                        if (url != null) {
                            it.enclosureUrl = url
                            it.enclosureType = tag.attrs["type"]?.trim()?.takeIf { t -> t.isNotEmpty() }
                            it.enclosureBytes = tag.attrs["length"]?.trim()?.toLongOrNull()?.takeIf { n -> n > 0 }
                        }
                    }
                }
                Ns.ITUNES -> when (e.local) {
                    "title" -> begin(Field.ITEM_ITUNES_TITLE, tag)
                    "duration" -> begin(Field.ITEM_DURATION, tag)
                    "summary" -> begin(Field.ITEM_SUMMARY, tag)
                    "image" -> if (it.imageUrl == null) it.imageUrl = PodcastIds.resolve(feedUrl, tag.attrs["href"])
                }
                Ns.CONTENT -> if (e.local == "encoded") begin(Field.ITEM_CONTENT, tag)
                Ns.DC -> if (e.local == "date") begin(Field.ITEM_DC_DATE, tag)
                Ns.PODCAST -> when (e.local) {
                    "chapters" -> if (it.chaptersUrl == null) {
                        it.chaptersUrl = PodcastIds.resolve(feedUrl, tag.attrs["url"])
                        it.chaptersType = tag.attrs["type"]?.trim()
                    }
                    "transcript" -> PodcastIds.resolve(feedUrl, tag.attrs["url"])?.let { url ->
                        if (it.transcripts.size < 20) {
                            it.transcripts += TranscriptLink(
                                url = url,
                                type = tag.attrs["type"]?.trim()?.lowercase(),
                                language = tag.attrs["language"]?.trim(),
                                rel = tag.attrs["rel"]?.trim(),
                            )
                        }
                    }
                }
                else -> Unit
            }
        }

        private fun isAudio(type: String?) = type?.startsWith("audio/") == true

        private fun begin(field: Field, tag: MarkupToken.StartTag) {
            if (tag.selfClosing) return
            capture = Capture(field, stack.size)
        }

        private fun finishCapture(c: Capture) {
            val text = c.text.toString()
            val target = item?.fields ?: show
            // The first of a field wins (some feeds repeat <title> or <description>).
            if (text.isNotBlank()) target.putIfAbsent(c.field, text)
        }

        private fun finishItem() {
            val b = item ?: return
            item = null
            itemDepth = -1
            val enclosure = b.enclosureUrl ?: return // a blog post, not an episode
            if (episodes.size >= MAX_EPISODES) return
            val guid = b.fields[Field.ITEM_GUID]?.trim()?.takeIf { it.isNotEmpty() }
            var id = PodcastIds.episodeIdFor(guid, enclosure)
            if (!seenIds.add(id)) {
                // The same guid twice: keep both if the audio differs, else it's a repeat.
                if (episodes.any { it.id == id && it.enclosureUrl == enclosure }) return
                id = PodcastIds.shortHash("${guid.orEmpty()}\n$enclosure")
                if (!seenIds.add(id)) return
            }
            val notes = listOf(Field.ITEM_CONTENT, Field.ITEM_DESCRIPTION, Field.ITEM_SUMMARY)
                .asSequence().map { HtmlToText.convert(b.fields[it]) }.firstOrNull { it.isNotEmpty() }.orEmpty()
            if (notes.isNotEmpty()) this.notes[id] = notes
            episodes += Episode(
                id = id,
                guid = guid,
                title = HtmlToText.inline(b.fields[Field.ITEM_TITLE])
                    .ifEmpty { HtmlToText.inline(b.fields[Field.ITEM_ITUNES_TITLE]) }
                    .ifEmpty { "Untitled episode" },
                publishedAt = FeedTime.parseDate(b.fields[Field.ITEM_DATE]) ?: FeedTime.parseDate(b.fields[Field.ITEM_DC_DATE]),
                durationMs = FeedTime.parseDuration(b.fields[Field.ITEM_DURATION]),
                enclosureUrl = enclosure,
                enclosureType = b.enclosureType,
                enclosureBytes = b.enclosureBytes,
                imageUrl = b.imageUrl,
                chaptersUrl = b.chaptersUrl,
                chaptersType = b.chaptersType,
                pscChapters = b.psc.toList(),
                transcripts = b.transcripts.toList(),
                hasNotes = notes.isNotEmpty(),
            )
        }

        /** A tag written back as markup inside a description (only the attributes that matter). */
        private fun renderTag(tag: MarkupToken.StartTag): String {
            val href = tag.attrs["href"]?.let { " href=\"" + it.replace("\"", "&quot;") + "\"" }.orEmpty()
            val start = tag.attrs["start"]?.let { " start=\"$it\"" }.orEmpty()
            return "<${tag.name}$href$start${if (tag.selfClosing) "/" else ""}>"
        }
    }
}
