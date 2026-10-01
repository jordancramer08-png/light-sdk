package com.thelightphone.listen.podcasts

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.thelightphone.listen.artwork.ArtPlaceholder
import com.thelightphone.listen.artwork.ArtSize
import com.thelightphone.listen.artwork.ArtworkCache
import com.thelightphone.listen.podcasts.net.PodcastFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small pictures from the web for search results (shows not followed yet, so there's no
 * art.jpg on the phone). Downloaded off the main thread, three at a time, shrunk to list size,
 * and kept in memory only (the most recent [MAX_KEPT]); nothing is saved.
 */
object RemoteArt {
    private const val MAX_KEPT = 60
    private val work = Dispatchers.IO.limitedParallelism(3)
    private val fetcher by lazy { PodcastFetcher() }

    /** Null value = tried, no picture. */
    private val kept = object : LinkedHashMap<String, ImageBitmap?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?) = size > MAX_KEPT
    }

    @Synchronized
    fun cached(url: String): Pair<Boolean, ImageBitmap?> = (url in kept) to kept[url]

    suspend fun load(url: String): ImageBitmap? {
        val (known, image) = cached(url)
        if (known) return image
        val loaded = withContext(work) {
            try {
                ArtworkCache.decodeBytes(fetcher.fetchArt(url), ArtSize.THUMB.px)
            } catch (e: Exception) {
                Log.w("Listen", "Couldn't get search art $url: $e")
                null
            }
        }
        synchronized(this) { kept[url] = loaded }
        return loaded
    }
}

/** A search result's picture, or a lettered block until (or unless) it arrives. */
@Composable
fun RemoteArtImage(url: String?, letter: String, modifier: Modifier) {
    var image by remember(url) { mutableStateOf(url?.let { RemoteArt.cached(it).second }) }
    if (image == null && url != null) {
        LaunchedEffect(url) { image = RemoteArt.load(url) }
    }
    val shown = image
    if (shown != null) {
        Image(bitmap = shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.clipToBounds())
    } else {
        ArtPlaceholder(letter, modifier)
    }
}
