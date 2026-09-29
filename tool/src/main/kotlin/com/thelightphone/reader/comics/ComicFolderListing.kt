package com.thelightphone.reader.comics

/** What a folder in the comics folder holds. Pure Kotlin, unit-tested. */
enum class ComicItemKind { FOLDER, COMIC, NOTE }

/** One thing in a comics folder. [path] is relative to the comics folder, with "/" between parts. */
data class ComicFolderItem(val name: String, val path: String, val kind: ComicItemKind) {
    val title: String get() = cleanComicTitle(name)
}

/** What a name in a folder is, or null when it isn't shown (other files, hidden files, Mac leftovers). */
fun comicItemKind(name: String, isDirectory: Boolean): ComicItemKind? = when {
    name.startsWith(".") || name.equals("__MACOSX", ignoreCase = true) -> null
    isDirectory -> ComicItemKind.FOLDER
    name.endsWith(".cbz", ignoreCase = true) -> ComicItemKind.COMIC
    name.endsWith(".txt", ignoreCase = true) -> ComicItemKind.NOTE
    else -> null
}

/**
 * One folder's contents in the order shown: subfolders first, then comics and notes mixed
 * together, each by file name ([NaturalOrder]), so numbered names keep their reading order.
 * [children] are (name, is a folder) pairs; [folder] is this folder's own path ("" = the top).
 */
fun folderItems(folder: String, children: List<Pair<String, Boolean>>): List<ComicFolderItem> {
    val items = children.mapNotNull { (name, isDirectory) ->
        comicItemKind(name, isDirectory)?.let { ComicFolderItem(name, childPath(folder, name), it) }
    }
    val byName = compareBy(NaturalOrder) { item: ComicFolderItem -> item.name }
    val folders = items.filter { it.kind == ComicItemKind.FOLDER }.sortedWith(byName)
    val files = items.filter { it.kind != ComicItemKind.FOLDER }.sortedWith(byName)
    return folders + files
}

/**
 * The comics and notes after [path] in its folder's [items] (as [folderItems] orders them),
 * for "Next in folder". Empty when [path] is last, or isn't among them.
 */
fun itemsAfter(items: List<ComicFolderItem>, path: String): List<ComicFolderItem> {
    val index = items.indexOfFirst { it.path == path }
    if (index < 0) return emptyList()
    return items.drop(index + 1).filter { it.kind != ComicItemKind.FOLDER }
}

fun childPath(folder: String, name: String): String = if (folder.isEmpty()) name else "$folder/$name"

/** "DC Comics/01. Book I" -> "DC Comics"; a top-level name -> "". */
fun parentPath(path: String): String = path.substringBeforeLast('/', "")

/** "3 folders · 12 comics", "1 comic", "Empty". Notes aren't counted. */
fun folderSummaryText(folders: Int, comics: Int): String {
    val parts = listOfNotNull(
        folders.takeIf { it > 0 }?.let { if (it == 1) "1 folder" else "$it folders" },
        comics.takeIf { it > 0 }?.let { if (it == 1) "1 comic" else "$it comics" },
    )
    return if (parts.isEmpty()) "Empty" else parts.joinToString(" · ")
}
