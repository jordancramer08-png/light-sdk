package com.jordan.watch.dev

import com.jordan.watch.logic.LibraryIndex
import com.jordan.watch.logic.Position

/** Release builds have no fake library (the real one is in src/debug). */
object DevLibrary {
    fun fakeIndex(): LibraryIndex? = null
    fun fakePositions(): Map<String, Position> = emptyMap()
}
