package com.thelightphone.listen.storage

import android.os.Environment
import java.io.File

/**
 * Every place Listen reads or writes on the phone's shared storage. Listen-Phone-Sync.cmd
 * depends on this layout, so it never changes. Nothing outside [root] is ever scanned.
 */
object ListenPaths {
    /** /sdcard/Listen */
    val root: File = File(Environment.getExternalStorageDirectory(), "Listen")

    /** Music/<Artist folder>/<Album folder>/<song files>, copied by the PC script. */
    val music: File = File(root, "Music")

    /** Audiobooks/<Author>/<...series...>/<Book folder>/, copied by the PC script. */
    val audiobooks: File = File(root, "Audiobooks")

    /** Listen's own state (playlists, positions, settings), backed up by the PC script. */
    val state: File = File(root, ".state")

    /** The PC script writes a new timestamp here after every change it makes. */
    val lastSync: File = File(state, "last-sync.txt")
}

/** Whether Listen has "All files access" (granted by option 9 of Listen-Phone-Sync.cmd). */
object StorageAccess {
    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()
}
