package com.thelightphone.listen.podcasts

import java.io.File
import java.time.OffsetDateTime

/** Made-up sample files in tool/src/test/resources/podcasts (tests run from the tool folder). */
object Samples {
    fun file(name: String) = File("src/test/resources/podcasts/$name")
    fun text(name: String) = file(name).readText(Charsets.UTF_8)

    /** "2026-09-29T10:00:00Z" as ms since 1970. */
    fun ms(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()
}
