package com.jordan.watch

import com.jordan.watch.logic.Browse
import com.jordan.watch.storage.WatchLibrary
import com.thelightphone.sdk.SimpleLightScreen

/**
 * Opens the right screen for a tile's id: a collection, a movie, a show or an episode.
 * (Movie and episode ids hold a "/", show ids never do, so they can't be mixed up.)
 */
fun SimpleLightScreen<*>.openTitle(id: String) {
    val library = WatchLibrary.state.value.library
    when {
        id.startsWith("collection:") -> navigateTo({ CollectionScreen(it, Browse.collectionName(id)) })
        library.movie(id) != null || library.episode(id) != null -> navigateTo({ DetailScreen(it, id) })
        library.show(id) != null -> navigateTo({ ShowScreen(it, id) })
    }
}
