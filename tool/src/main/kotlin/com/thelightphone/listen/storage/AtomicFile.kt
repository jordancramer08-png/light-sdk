package com.thelightphone.listen.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Whole-file text writes that never leave a half-written file behind: the text goes to
 * `name.tmp` first, is flushed to disk, and then renamed over `name`. If the phone dies
 * mid-write, the old file is still there.
 */
object AtomicFile {

    fun writeText(file: File, text: String) {
        val dir = file.absoluteFile.parentFile ?: throw IOException("No folder for $file")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Can't create $dir")
        val tmp = File(dir, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            // Some file systems won't rename over an existing file.
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Can't replace $file")
        }
    }

    /** The file's text, or null if it's missing or can't be read. */
    fun readTextOrNull(file: File): String? =
        try {
            if (file.isFile) file.readText(Charsets.UTF_8) else null
        } catch (e: IOException) {
            null
        }
}
