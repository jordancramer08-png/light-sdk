package com.thelightphone.reader.comics

/**
 * The PC tool leaves out ComicDetails.kt (it needs the app's XML and JSON readers), but
 * ComicPages.kt still names this one function from it. Never called by the tool.
 */
fun comicInfoEntry(entryNames: List<String>): String? =
    entryNames.firstOrNull { it.substringAfterLast('/').equals("ComicInfo.xml", ignoreCase = true) }
