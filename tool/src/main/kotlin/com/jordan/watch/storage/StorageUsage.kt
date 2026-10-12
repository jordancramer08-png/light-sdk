package com.jordan.watch.storage

import java.io.File

/** How much /sdcard/Watch takes up, and how much room the phone has left. */
object StorageUsage {

    /** Total bytes of every file under [root] (videos, posters, json). 0 if it can't be read. */
    fun usedBytes(root: File = WatchPaths.root): Long =
        runCatching { root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)

    /** Free bytes on the phone's shared storage, or null if unknown. */
    fun freeBytes(root: File = WatchPaths.root): Long? {
        val dir = if (root.exists()) root else root.parentFile ?: return null
        return runCatching { dir.usableSpace }.getOrNull()?.takeIf { it > 0 }
    }
}
