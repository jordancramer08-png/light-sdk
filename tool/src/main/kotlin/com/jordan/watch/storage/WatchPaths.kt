package com.jordan.watch.storage

import android.os.Environment
import java.io.File

/**
 * Every place Watch reads or writes on the phone. Watch-Phone-Sync.cmd depends on this
 * layout, so it never changes. Nothing outside [root] is ever scanned.
 */
object WatchPaths {
    /** /sdcard/Watch */
    val root: File = File(Environment.getExternalStorageDirectory(), "Watch")
    val movies: File = File(root, "Movies")
    val tv: File = File(root, "TV")

    /** Positions, watched marks, watchlist and settings, backed up by the PC script. */
    val state: File = File(root, ".state")

    /** The PC script writes a new timestamp here after every change it makes. */
    val lastSync: File = File(state, "last-sync.txt")
}

/** Whether Watch has "All files access" (granted by Watch-Phone-Sync.cmd). */
object StorageAccess {
    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()
}
