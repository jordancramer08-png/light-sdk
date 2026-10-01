package com.thelightphone.listen.podcasts.xml

import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtmlAndMarkupTest {

    @Test
    fun `entities decode, and impossible characters become a replacement mark`() {
        assertEquals("A & B — “quoted” ’", decodeEntities("A &amp; B &mdash; &ldquo;quoted&rdquo; &#x2019;"))
        assertEquals("����", decodeEntities("&#0;&#xD800;&#x110000;&#99999999;"))
        assertEquals("&unknown;", decodeEntities("&unknown;"))
        assertEquals("😀", decodeEntities("&#128512;"))
    }

    @Test
    fun `namespace prefixes are kept and names lower-cased`() {
        val tokens = MarkupReader.tokens("<itunes:Image HREF='x'/><psc:chapters><podcast:chapters url=\"y\"></podcast:chapters>").toList()
        assertEquals(MarkupToken.StartTag("itunes:image", mapOf("href" to "x"), selfClosing = true), tokens[0])
        assertEquals("psc:chapters", (tokens[1] as MarkupToken.StartTag).name)
        assertEquals("podcast:chapters", (tokens[2] as MarkupToken.StartTag).name)
    }

    @Test
    fun `DOCTYPE with an internal subset is skipped whole, and entities in it are never used`() {
        val xml = """<!DOCTYPE x [ <!ENTITY a "AAAA"> <!ENTITY b "&a;&a;"> ]><t>&b;</t>"""
        val tokens = MarkupReader.tokens(xml).toList()
        assertEquals(listOf<MarkupToken>(MarkupToken.StartTag("t", emptyMap(), false), MarkupToken.Text("&b;"), MarkupToken.EndTag("t")), tokens)
    }

    @Test
    fun `comments, CDATA with brackets, and a lone less-than`() {
        val tokens = MarkupReader.tokens("<!-- <b>no</b> ---><a><![CDATA[x]] ]]>y]]></a>1 < 2").toList()
        assertEquals(MarkupToken.StartTag("a", emptyMap(), false), tokens[0], "the comment is skipped")
        assertEquals(MarkupToken.Text("x]] ", cdata = true), tokens[1])
        assertEquals(MarkupToken.Text("y]]>"), tokens[2], "text after CDATA (a stray ]]> is just text)")
        assertEquals("1 < 2", tokens.drop(4).joinToString("") { (it as MarkupToken.Text).text })
    }

    @Test
    fun `quoted greater-than inside an attribute doesn't end the tag`() {
        val t = MarkupReader.tokens("""<a title="1 > 0" href='x'>go</a>""").first() as MarkupToken.StartTag
        assertEquals(mapOf("title" to "1 > 0", "href" to "x"), t.attrs)
    }

    @Test
    fun `text and tags are capped`() {
        val r = MarkupReader(StringReader("<a>" + "x".repeat(5_000) + "</a><" + "b ".repeat(5_000) + "><c/>"), maxTextChars = 100, maxTagChars = 100)
        assertEquals(MarkupToken.StartTag("a", emptyMap(), false), r.next())
        assertEquals(100, (r.next() as MarkupToken.Text).text.length)
        assertEquals(MarkupToken.EndTag("a"), r.next())
        assertEquals(MarkupToken.StartTag("c", emptyMap(), true), r.next(), "the over-long tag is dropped")
    }

    @Test
    fun `plain text keeps its lines`() {
        assertEquals("One\nTwo\n\nThree & four", HtmlToText.convert("One\r\nTwo\n\n\n\nThree &amp; four  "))
        assertEquals("", HtmlToText.convert(null))
        assertEquals("", HtmlToText.convert("<p> </p><img src='x'><script>bad()</script>"))
    }

    @Test
    fun `links show their address only when the text doesn't`() {
        assertEquals("site (https://e.com/a)", HtmlToText.convert("<a href='https://e.com/a'>site</a>"))
        assertEquals("www.e.com/a", HtmlToText.convert("<a href='https://e.com/a/'>www.e.com/a</a>"))
        assertEquals("https://e.com", HtmlToText.convert("<a href='https://e.com'></a>"))
        assertEquals("chapter 3", HtmlToText.convert("<a href='#ch3'>chapter 3</a>"), "page anchors aren't addresses")
    }

    @Test
    fun `inline text for titles`() {
        assertEquals("It’s a <b>test</b>", HtmlToText.inline("It&amp;#8217;s a &lt;b&gt;test&lt;/b&gt;"))
        assertEquals("Bold title", HtmlToText.inline("<b>Bold</b>   title"))
        assertTrue(HtmlToText.inline("  ").isEmpty())
    }
}
