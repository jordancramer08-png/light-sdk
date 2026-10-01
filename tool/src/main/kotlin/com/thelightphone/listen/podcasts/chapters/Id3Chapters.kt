package com.thelightphone.listen.podcasts.chapters

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Reads ID3v2 `CHAP` chapter frames (and the `CTOC` order) from the start of a downloaded
 * MP3. Only the tag header and the chapter frames are read; every other frame (a 3 MB
 * cover picture, say) is skipped by seeking past it, so the file is never loaded whole.
 * Chapter pictures are ignored. Never throws; null when there are no readable chapters.
 */
object Id3Chapters {

    /** Tags claiming to be bigger than this are junk. */
    private const val MAX_TAG_BYTES = 16 * 1024 * 1024

    /** How much of one CHAP/CTOC frame is read; the title comes first, pictures after. */
    private const val MAX_FRAME_READ = 64 * 1024

    /** A whole tag is only read into memory to undo v2.3 "unsynchronisation" up to this size. */
    private const val MAX_UNSYNC_TAG_BYTES = 4 * 1024 * 1024

    fun read(file: File): ChapterList? =
        try {
            RandomAccessFile(file, "r").use { read(it) }
        } catch (e: Exception) {
            null
        }

    private fun read(raf: RandomAccessFile): ChapterList? {
        val header = ByteArray(10)
        if (raf.length() < 10) return null
        raf.readFully(header)
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return null
        val version = header[3].toInt()
        if (version != 3 && version != 4) return null
        val flags = header[5].toInt() and 0xFF
        val tagSize = synchsafe(header, 6) ?: return null
        if (tagSize <= 0 || tagSize > MAX_TAG_BYTES) return null
        val unsync = flags and 0x80 != 0
        val tagEnd = 10L + tagSize

        if (unsync && version == 3) {
            // The whole v2.3 tag is unsynchronised: read it, undo that, and walk it in memory.
            if (tagSize > MAX_UNSYNC_TAG_BYTES || tagEnd > raf.length()) return null
            val bytes = ByteArray(tagSize)
            raf.readFully(bytes)
            return walk(ByteSource(resync(bytes)), version, hasExtendedHeader = flags and 0x40 != 0)
        }
        return walk(FileSource(raf, minOf(tagEnd, raf.length())), version, hasExtendedHeader = flags and 0x40 != 0)
    }

    private fun walk(src: Source, version: Int, hasExtendedHeader: Boolean): ChapterList? {
        if (hasExtendedHeader) {
            val ext = src.read(4) ?: return null
            val size = if (version == 4) synchsafe(ext, 0) ?: return null else plain(ext, 0)
            // v2.4 counts the size field itself; v2.3 doesn't.
            val skip = if (version == 4) size - 4 else size
            if (skip < 0 || !src.skip(skip.toLong())) return null
        }
        val chapters = mutableMapOf<String, Chapter>()
        var order: List<String>? = null
        while (src.remaining() >= 10) {
            val fh = src.read(10) ?: break
            if (fh[0].toInt() == 0) break // padding
            val id = String(fh, 0, 4, Charsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val size = (if (version == 4) synchsafe(fh, 4) else plain(fh, 4)) ?: break
            if (size < 0 || size > src.remaining()) break
            val frameFlags = ((fh[8].toInt() and 0xFF) shl 8) or (fh[9].toInt() and 0xFF)
            if ((id == "CHAP" || id == "CTOC") && readable(version, frameFlags)) {
                var body = src.read(minOf(size, MAX_FRAME_READ)) ?: break
                if (size > MAX_FRAME_READ && !src.skip((size - MAX_FRAME_READ).toLong())) break
                body = frameBody(version, frameFlags, body)
                if (id == "CHAP") parseChap(body, version)?.let { (elementId, chapter) -> chapters[elementId] = chapter }
                else parseCtoc(body)?.let { (topLevel, children) -> if (topLevel || order == null) order = children }
            } else if (!src.skip(size.toLong())) {
                break
            }
        }
        if (chapters.isEmpty()) return null
        val ordered = order?.mapNotNull { chapters[it] }?.takeIf { it.isNotEmpty() } ?: chapters.values.toList()
        return normalizeChapters(ChapterSource.ID3, ordered)
    }

    /** Compressed or encrypted frames can't be read here. */
    private fun readable(version: Int, flags: Int): Boolean =
        if (version == 4) flags and 0x000C == 0 else flags and 0x00C0 == 0

    /** Undoes a v2.4 frame's own unsynchronisation and data-length field. */
    private fun frameBody(version: Int, flags: Int, body: ByteArray): ByteArray {
        if (version != 4) return body
        var b = body
        if (flags and 0x0001 != 0 && b.size >= 4) b = b.copyOfRange(4, b.size)
        if (flags and 0x0002 != 0) b = resync(b)
        return b
    }

    /** CHAP: element id, start ms, end ms, two offsets, then sub-frames (TIT2 = title, WXXX = link). */
    private fun parseChap(body: ByteArray, version: Int): Pair<String, Chapter>? {
        val idEnd = body.indexOfZero(0) ?: return null
        val elementId = String(body, 0, idEnd, Charsets.ISO_8859_1)
        var p = idEnd + 1
        if (p + 16 > body.size) return null
        val start = plain(body, p).toLong() and 0xFFFFFFFFL
        val end = plain(body, p + 4).toLong() and 0xFFFFFFFFL
        p += 16
        var title = ""
        var url: String? = null
        while (p + 10 <= body.size) {
            val id = String(body, p, 4, Charsets.ISO_8859_1)
            if (body[p].toInt() == 0 || !id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val size = (if (version == 4) synchsafe(body, p + 4) else plain(body, p + 4)) ?: break
            val dataStart = p + 10
            if (size < 0 || dataStart + size > body.size) {
                // A sub-frame cut off by MAX_FRAME_READ: use what's there for the title only.
                if (id == "TIT2" && title.isEmpty()) title = text(body.copyOfRange(dataStart, body.size))
                break
            }
            val data = body.copyOfRange(dataStart, dataStart + size)
            when (id) {
                "TIT2" -> title = text(data)
                "WXXX" -> url = wxxx(data)
            }
            p = dataStart + size
        }
        val endMs = end.takeIf { it != 0xFFFFFFFFL && it > start }
        return elementId to Chapter(title = title.trim(), startMs = start, endMs = endMs, url = url)
    }

    /** CTOC: element id, flags (0x02 = top level), entry count, child element ids. */
    private fun parseCtoc(body: ByteArray): Pair<Boolean, List<String>>? {
        val idEnd = body.indexOfZero(0) ?: return null
        var p = idEnd + 1
        if (p + 2 > body.size) return null
        val topLevel = body[p].toInt() and 0x02 != 0
        val count = body[p + 1].toInt() and 0xFF
        p += 2
        val children = mutableListOf<String>()
        repeat(count) {
            val e = body.indexOfZero(p) ?: return@repeat
            children += String(body, p, e - p, Charsets.ISO_8859_1)
            p = e + 1
        }
        return topLevel to children
    }

    /** A text frame: encoding byte, then text in that encoding (ends at the first terminator). */
    private fun text(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val enc = data[0].toInt()
        val bytes = data.copyOfRange(1, data.size)
        return decode(enc, bytes).substringBefore('\u0000').trim()
    }

    /** WXXX: encoding, a description ending in a terminator, then the address in Latin-1. */
    private fun wxxx(data: ByteArray): String? {
        if (data.isEmpty()) return null
        val enc = data[0].toInt()
        var p = 1
        if (enc == 1 || enc == 2) {
            while (p + 1 < data.size && !(data[p].toInt() == 0 && data[p + 1].toInt() == 0)) p += 2
            p += 2
        } else {
            while (p < data.size && data[p].toInt() != 0) p++
            p += 1
        }
        if (p >= data.size) return null
        return String(data, p, data.size - p, Charsets.ISO_8859_1).substringBefore('\u0000').trim()
            .takeIf { it.startsWith("http") }
    }

    private fun decode(enc: Int, bytes: ByteArray): String = when (enc) {
        1 -> String(bytes, Charsets.UTF_16) // with a byte-order mark
        2 -> String(bytes, Charsets.UTF_16BE)
        3 -> String(bytes, Charsets.UTF_8)
        else -> String(bytes, Charsets.ISO_8859_1)
    }

    /** A 4-byte "synchsafe" number (7 bits per byte), or null when a high bit is set. */
    private fun synchsafe(b: ByteArray, at: Int): Int? {
        var v = 0
        for (i in 0 until 4) {
            val x = b[at + i].toInt() and 0xFF
            if (x and 0x80 != 0) return null
            v = (v shl 7) or x
        }
        return v
    }

    private fun plain(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    /** Undoes unsynchronisation: every 0xFF 0x00 becomes 0xFF. */
    private fun resync(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if ((b[i].toInt() and 0xFF) == 0xFF && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    private fun ByteArray.indexOfZero(from: Int): Int? {
        for (i in from until size) if (this[i].toInt() == 0) return i
        return null
    }

    /** Bytes read in order, from the file or from memory. */
    private interface Source {
        fun remaining(): Long
        fun read(n: Int): ByteArray?
        fun skip(n: Long): Boolean
    }

    private class FileSource(private val raf: RandomAccessFile, private val end: Long) : Source {
        override fun remaining() = end - raf.filePointer
        override fun read(n: Int): ByteArray? {
            if (n > remaining()) return null
            return ByteArray(n).also { raf.readFully(it) }
        }
        override fun skip(n: Long): Boolean {
            if (n > remaining()) return false
            raf.seek(raf.filePointer + n)
            return true
        }
    }

    private class ByteSource(private val bytes: ByteArray) : Source {
        private var pos = 0
        override fun remaining() = (bytes.size - pos).toLong()
        override fun read(n: Int): ByteArray? {
            if (n > remaining()) return null
            return bytes.copyOfRange(pos, pos + n).also { pos += n }
        }
        override fun skip(n: Long): Boolean {
            if (n > remaining()) return false
            pos += n.toInt()
            return true
        }
    }
}
