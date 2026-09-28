package com.thelightphone.reader.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarkupParserTest {

    @Test
    fun tokenizesTagsAttributesAndText() {
        val tokens = tokenizeMarkup("""<p class="a">Hello</p>""")
        assertEquals(
            listOf(
                MarkupToken.StartTag("p", mapOf("class" to "a"), false),
                MarkupToken.Text("Hello"),
                MarkupToken.EndTag("p"),
            ),
            tokens,
        )
    }

    @Test
    fun stripsNamespacePrefixFromTagNames() {
        val tokens = tokenizeMarkup("<dc:title>Book</dc:title>")
        assertEquals("title", (tokens[0] as MarkupToken.StartTag).name)
        assertEquals("title", (tokens[2] as MarkupToken.EndTag).name)
    }

    @Test
    fun keepsNamespacePrefixOnAttributeNames() {
        val tokens = tokenizeMarkup("""<nav epub:type="toc">""")
        val tag = tokens[0] as MarkupToken.StartTag
        assertEquals("toc", tag.attrs["epub:type"])
    }

    @Test
    fun treatsExplicitSelfCloseAsSelfClosing() {
        val tokens = tokenizeMarkup("""<img src="x.jpg"/>""")
        assertEquals(true, (tokens[0] as MarkupToken.StartTag).selfClosing)
    }

    @Test
    fun treatsBareBrAsVoidWithoutTrailingSlash() {
        val tokens = tokenizeMarkup("<br>")
        assertEquals(true, (tokens[0] as MarkupToken.StartTag).selfClosing)
    }

    @Test
    fun skipsCommentsAndProcessingInstructions() {
        val tokens = tokenizeMarkup("<?xml version=\"1.0\"?><!-- note --><p>x</p>")
        assertEquals(
            listOf(MarkupToken.StartTag("p", emptyMap(), false), MarkupToken.Text("x"), MarkupToken.EndTag("p")),
            tokens,
        )
    }

    @Test
    fun decodesNamedNumericAndHexEntities() {
        assertEquals("café & bar—done…", decodeEntities("caf&eacute; &amp; bar&#8212;done&#x2026;"))
    }

    @Test
    fun leavesUnknownEntityLiteral() {
        assertEquals("&notarealentity;", decodeEntities("&notarealentity;"))
    }

    @Test
    fun xmlTreeNavigationHelpersFindByLocalName() {
        val root = parseXml(
            """<package><metadata><dc:title>T</dc:title></metadata>
               <manifest><item id="a"/><item id="b"/></manifest></package>""",
        )
        assertEquals("T", firstDescendant(root, "title")?.let { directText(it) })
        assertEquals(listOf("a", "b"), directChildren(firstDescendant(root, "manifest")!!, "item").map { it.attrs["id"] })
    }

    @Test
    fun allDescendantsFindsNestedMatchesEverywhere() {
        val root = parseXml("<a><b><x/></b><c><x/></c></a>")
        assertEquals(2, allDescendants(root, "x").size)
    }

    @Test
    fun allTextConcatenatesNestedTextNodes() {
        val root = parseXml("<a>Part of a link, <span>even</span> nested</a>")
        val a = firstDescendant(root, "a")!!
        assertEquals("Part of a link, even nested", allText(a))
    }

    @Test
    fun directTextIgnoresNestedElements() {
        val root = parseXml("<a>outer<b>inner</b>tail</a>")
        val a = firstDescendant(root, "a")!!
        assertEquals("outertail", directText(a))
    }

    @Test
    fun toleratesMismatchedClosingTags() {
        // A stray, unmatched </b> shouldn't crash the parser or corrupt the tree.
        val root = parseXml("<a>hello</b><c>world</c></a>")
        assertEquals("world", firstDescendant(root, "c")?.let { directText(it) })
    }

    @Test
    fun missingElementReturnsNull() {
        val root = parseXml("<a><b/></a>")
        assertNull(firstDescendant(root, "nonexistent"))
    }
}
