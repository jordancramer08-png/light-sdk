package com.jordan.watch.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout

/**
 * Turns the content 90 degrees per [turns] (1 = landscape on the portrait screen). The
 * content is measured with width and height swapped, so it fills the turned space, and
 * touches are turned with it. Nothing is rebuilt, so a playing video keeps playing.
 */
fun Modifier.quarterTurned(turns: Int): Modifier {
    val turned = turns.mod(4) % 2 == 1
    return this
        .layout { measurable, constraints ->
            val inner = if (turned) {
                constraints.copy(
                    minWidth = constraints.minHeight, maxWidth = constraints.maxHeight,
                    minHeight = constraints.minWidth, maxHeight = constraints.maxWidth,
                )
            } else {
                constraints
            }
            val placeable = measurable.measure(inner)
            val w = if (turned) placeable.height else placeable.width
            val h = if (turned) placeable.width else placeable.height
            layout(w, h) {
                placeable.place((w - placeable.width) / 2, (h - placeable.height) / 2)
            }
        }
        .graphicsLayer { rotationZ = 90f * turns.mod(4) }
}
