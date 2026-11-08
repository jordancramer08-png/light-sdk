package com.thelightphone.adventure

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Licenses screen joins hand-wrapped lines so the phone wraps each paragraph once. */
class LicensesTextTest {
    private val credits = reflowParagraphs(File("src/main/assets/CREDITS.txt").readText())
    private val lines = credits.split('\n')

    @Test
    fun wrappedParagraphsBecomeOneLine() {
        assertTrue(
            lines.any {
                it.startsWith("The three story files in this folder are the prebuilt files from the COMPILED folder of each") &&
                    it.endsWith("on November 20, 2025.")
            },
            credits,
        )
    }

    @Test
    fun bulletsKeepTheirOwnLinesAndJoinTheirIndentedRest() {
        val bullets = lines.filter { it.startsWith("- ") }
        assertEquals(2, bullets.size, credits)
        assertTrue(bullets[0].endsWith("Infocom, Activision or Microsoft product."), bullets[0])
    }

    @Test
    fun tableRowsStaySeparateAndUnderlinesGo() {
        assertEquals(6, lines.count { it.startsWith("  Zork") }, credits)
        assertFalse(lines.any { it.isNotEmpty() && it.all { c -> c == '=' } }, credits)
        assertTrue("UNOFFICIAL FAN MODIFICATIONS" in credits)
    }
}
