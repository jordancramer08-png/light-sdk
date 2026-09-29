package com.thelightphone.reader

/**
 * The wording of "Remove from phone" (Book Details, Comic Details, a comics folder's menu),
 * in plain Kotlin so it can be unit-tested on the PC. No Android here.
 */

/** What a removal takes off the phone: how many comics and notes, and the bytes it frees (caches included). */
data class RemovalSummary(val comics: Int = 0, val notes: Int = 0, val bytes: Long = 0)

/** Said under every question: removing never forgets where you were. */
const val REMOVAL_KEEPS = "Places, status and lists are kept, so anything sent again picks up where you left off."

/** One book or comic: "Remove “Action Comics #1” (45 MB)?" */
fun itemRemovalQuestion(title: String, bytes: Long): String = "Remove “$title” (${fileSizeText(bytes)})?"

/** A folder: "Remove 12 comics (1.4 GB)?", "Remove 12 comics and 1 note (1.4 GB)?", or "Remove this empty folder?". */
fun folderRemovalQuestion(summary: RemovalSummary): String {
    val parts = listOfNotNull(
        summary.comics.takeIf { it > 0 }?.let { if (it == 1) "1 comic" else "$it comics" },
        summary.notes.takeIf { it > 0 }?.let { if (it == 1) "1 note" else "$it notes" },
    )
    if (parts.isEmpty()) return "Remove this empty folder?"
    return "Remove ${parts.joinToString(" and ")} (${fileSizeText(summary.bytes)})?"
}
