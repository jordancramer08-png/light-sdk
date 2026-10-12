package com.jordan.watch.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import com.jordan.watch.logic.Shelves
import com.jordan.watch.logic.Tile
import com.jordan.watch.storage.Poster
import com.jordan.watch.storage.PosterCache
import com.jordan.watch.storage.PosterSize
import com.jordan.watch.storage.WatchPaths
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import java.io.File

/**
 * [tile]'s poster filling [modifier]'s box (cropped). Drawn at once when it's in memory,
 * otherwise loaded off the main thread by [PosterCache]. No poster: a colored block with
 * the title's first letter. (Same as Listen's ArtImage.)
 */
@Composable
fun PosterImage(tile: Tile, modifier: Modifier = Modifier, size: PosterSize = PosterSize.GRID) {
    val file = remember(tile.poster) { tile.poster?.let { File(WatchPaths.root, it) } }
    var poster by remember(file, size) { mutableStateOf(file?.let { PosterCache.cached(it, size) }) }
    if (poster == null && file != null) {
        LaunchedEffect(file, size) { poster = PosterCache.load(file, size) }
    }
    when (val shown = poster) {
        is Poster.Found -> Image(
            bitmap = shown.image,
            contentDescription = null, // the title is written under it
            contentScale = ContentScale.Crop,
            modifier = modifier.clipToBounds(),
        )
        // Missing, or still loading: the placeholder (letter only once we know there's none).
        else -> PosterPlaceholder(tile.title, showLetter = shown == Poster.Missing || file == null, modifier = modifier)
    }
}

/** A block in the title's own muted color, with its first letter in the middle. */
@Composable
fun PosterPlaceholder(title: String, showLetter: Boolean, modifier: Modifier = Modifier) {
    val color = remember(title) { Color.hsv(Shelves.placeholderHue(title), 0.45f, 0.38f) }
    Box(modifier = modifier.background(color), contentAlignment = Alignment.Center) {
        if (showLetter) {
            LightText(text = Shelves.placeholderLetter(title), variant = LightTextVariant.Subtitle, color = Color.White)
        }
    }
}
