package com.thelightphone.reader.epub

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class CoverTest {

    /** Stand-in image bytes: the finder never decodes them, so any distinct bytes will do. */
    private fun image(label: String) = "image:$label".toByteArray()

    private val text = listOf(EpubFixture.chapter("c1", "One", listOf(EpubFixture.longParagraph("A"))))

    private fun imageItem(id: String, href: String, properties: String? = null) =
        """<item id="$id" href="$href" media-type="image/jpeg"${properties?.let { " properties=\"$it\"" } ?: ""}/>"""

    private fun coverOf(
        chapters: List<EpubFixture.ChapterSpec> = text,
        metadata: String = "",
        manifest: String = "",
        files: Map<String, ByteArray> = emptyMap(),
    ): ByteArray? = EpubParser.parse(
        EpubFixture.build(chapters = chapters, extraMetadata = metadata, extraManifest = manifest, rawEntries = files),
    ).cover

    @Test
    fun metaNameCoverWins() {
        val cover = coverOf(
            metadata = """<meta name="cover" content="front"/>""",
            manifest = imageItem("front", "images/front.jpg") + imageItem("cover-back", "images/cover-back.jpg"),
            files = mapOf("OEBPS/images/front.jpg" to image("front"), "OEBPS/images/cover-back.jpg" to image("back")),
        )
        assertContentEquals(image("front"), cover)
    }

    @Test
    fun metaCoverMayGiveThePathInsteadOfAnId() {
        val cover = coverOf(
            metadata = """<meta name="cover" content="images/front.jpg"/>""",
            manifest = imageItem("img1", "images/front.jpg"),
            files = mapOf("OEBPS/images/front.jpg" to image("front")),
        )
        assertContentEquals(image("front"), cover)
    }

    @Test
    fun epub3CoverImageProperty() {
        val cover = coverOf(
            manifest = imageItem("pic1", "images/pic1.jpg") + imageItem("pic2", "images/pic2.jpg", "cover-image"),
            files = mapOf("OEBPS/images/pic1.jpg" to image("1"), "OEBPS/images/pic2.jpg" to image("2")),
        )
        assertContentEquals(image("2"), cover)
    }

    @Test
    fun imageNamedCoverById() {
        val cover = coverOf(
            manifest = imageItem("map", "images/map.jpg") + imageItem("my-cover", "images/i002.jpg"),
            files = mapOf("OEBPS/images/map.jpg" to image("map"), "OEBPS/images/i002.jpg" to image("cover")),
        )
        assertContentEquals(image("cover"), cover)
    }

    @Test
    fun imageNamedCoverByFileName() {
        val cover = coverOf(
            manifest = imageItem("map", "images/map.jpg") + imageItem("i2", "images/Cover.JPG"),
            files = mapOf("OEBPS/images/map.jpg" to image("map"), "OEBPS/images/Cover.JPG" to image("cover")),
        )
        assertContentEquals(image("cover"), cover)
    }

    @Test
    fun aMissingCoverFileFallsThroughToTheNextChoice() {
        val cover = coverOf(
            metadata = """<meta name="cover" content="gone"/>""",
            manifest = imageItem("gone", "images/gone.jpg") + imageItem("pic", "images/pic.jpg", "cover-image"),
            files = mapOf("OEBPS/images/pic.jpg" to image("pic")),
        )
        assertContentEquals(image("pic"), cover)
    }

    @Test
    fun lastResortIsTheFirstImageInTheFirstSpineFile() {
        // The cover page sits in a subfolder, is marked linear="no" and isn't in the contents.
        val coverPage = EpubFixture.ChapterSpec(
            "page", "text/page.xhtml", """<div><img src="../art/p1.jpg"/></div>""", linear = false,
        )
        val cover = coverOf(
            chapters = listOf(coverPage) + text,
            manifest = imageItem("p1", "art/p1.jpg"),
            files = mapOf("OEBPS/art/p1.jpg" to image("p1")),
        )
        assertContentEquals(image("p1"), cover)
    }

    @Test
    fun svgImageOnTheCoverPageCounts() {
        val coverPage = EpubFixture.ChapterSpec(
            "page", "page.xhtml",
            """<svg xmlns:xlink="http://www.w3.org/1999/xlink"><image width="600" height="800" xlink:href="art/p1.jpg"/></svg>""",
        )
        val cover = coverOf(
            chapters = listOf(coverPage) + text,
            files = mapOf("OEBPS/art/p1.jpg" to image("p1")),
        )
        assertContentEquals(image("p1"), cover)
    }

    @Test
    fun onlyTheFirstSpineFileIsLookedAt() {
        val withPicture = EpubFixture.ChapterSpec(
            "c2", "c2.xhtml", """<p>${EpubFixture.longParagraph("B")}</p><img src="map.jpg"/>""", navTitle = "Two",
        )
        val cover = coverOf(chapters = text + withPicture, files = mapOf("OEBPS/map.jpg" to image("map")))
        assertNull(cover)
    }

    @Test
    fun noImagesMeansNoCover() {
        assertNull(coverOf())
    }
}
