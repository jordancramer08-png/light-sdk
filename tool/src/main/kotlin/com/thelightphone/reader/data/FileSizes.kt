package com.thelightphone.reader.data

import java.io.File

/** The bytes a file takes, or everything in a folder and the folders under it. 0 when it's missing. */
fun bytesUnder(file: File): Long =
    if (file.isFile) file.length() else file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
