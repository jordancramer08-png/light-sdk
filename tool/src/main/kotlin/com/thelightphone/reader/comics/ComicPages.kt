package com.thelightphone.reader.comics

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * The pages of a CBZ (a zip of pictures), in plain Kotlin so it runs in PC tests.
 * No Android here.
 */

private val PAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

/** A cover picture bigger than this is not read (a broken or odd file); the comic just has no cover. */
const val MAX_COVER_BYTES = 30L * 1024 * 1024

/** A page picture bigger than this (compressed) isn't shown or looked at: a broken or odd file. */
const val MAX_PAGE_BYTES = 60L * 1024 * 1024

/**
 * True when a zip entry is a page: a picture, not a folder, not hidden ("._001.jpg",
 * ".DS_Store"), and not in a Mac `__MACOSX` folder.
 */
fun isPageImage(entryName: String): Boolean {
    val parts = entryName.replace('\\', '/').split('/')
    val fileName = parts.last()
    if (fileName.isEmpty() || fileName.startsWith(".")) return false
    if (parts.any { it.equals("__MACOSX", ignoreCase = true) }) return false
    return fileName.substringAfterLast('.', "").lowercase() in PAGE_EXTENSIONS
}

/** The page entries in reading order: by name, numbers as numbers ("2.jpg" before "10.jpg"). */
fun comicPages(entryNames: Iterable<String>): List<String> =
    entryNames.filter(::isPageImage).sortedWith(NaturalOrder)

/** Opens a CBZ. Old zips that don't store names as UTF-8 are read as the DOS code page instead. */
fun openComicZip(file: File): ZipFile =
    try {
        ZipFile(file)
    } catch (e: ZipException) {
        ZipFile(file, Charset.forName("IBM437"))
    } catch (e: IllegalArgumentException) {
        ZipFile(file, Charset.forName("IBM437"))
    }

/** A CBZ's pages, in reading order. Throws if it isn't a readable zip. */
fun readComicPages(file: File): List<String> =
    openComicZip(file).use { zip ->
        comicPages(zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList())
    }

/** A ComicInfo.xml bigger than this isn't read (it is only a few KB normally). */
private const val MAX_COMIC_INFO_BYTES = 1L * 1024 * 1024

/** The text of the CBZ's ComicInfo.xml, or null when it has none. Throws if it isn't a readable zip. */
fun readComicInfo(file: File): String? =
    openComicZip(file).use { zip ->
        val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
        val entry = comicInfoEntry(names) ?: return null
        readEntry(zip, entry, MAX_COMIC_INFO_BYTES)?.let(::decodeNoteText)
    }

/** One page's bytes, or null when it's missing or bigger than [maxBytes]. */
fun readComicEntry(file: File, entryName: String, maxBytes: Long = MAX_COVER_BYTES): ByteArray? =
    openComicZip(file).use { zip -> readEntry(zip, entryName, maxBytes) }

private fun readEntry(zip: ZipFile, entryName: String, maxBytes: Long): ByteArray? {
    val entry = zip.getEntry(entryName) ?: return null
    if (entry.size > maxBytes) return null
    return zip.getInputStream(entry).use { it.readBytes() }
}

/**
 * A CBZ kept open while its comic is being read: the zip's list of files is read once, not
 * again for every page (a 600-page omnibus has a long list). Safe to use from several
 * threads. After [close], every read gives null.
 */
class OpenComic(private val file: File) : Closeable {
    private var zip: ZipFile? = null
    private var closed = false

    /** One page's bytes, or null when it's missing, bigger than [maxBytes], or the comic is closed. */
    fun read(entryName: String, maxBytes: Long = MAX_PAGE_BYTES): ByteArray? {
        val zip = open() ?: return null
        return readEntry(zip, entryName, maxBytes)
    }

    @Synchronized
    private fun open(): ZipFile? {
        if (closed) return null
        return zip ?: openComicZip(file).also { zip = it }
    }

    @Synchronized
    override fun close() {
        closed = true
        zip?.close()
        zip = null
    }
}

/** A note file's text: UTF-8 (a leading byte-order mark dropped), or Windows-1252 when it isn't UTF-8. */
fun decodeNoteText(bytes: ByteArray): String {
    val utf8 = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = try {
        utf8.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1252"))
    }
    return text.removePrefix("﻿").replace("\r\n", "\n")
}
