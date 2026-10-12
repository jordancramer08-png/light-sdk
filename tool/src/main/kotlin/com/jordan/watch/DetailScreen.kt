package com.jordan.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.jordan.watch.logic.AccentColor
import com.jordan.watch.logic.Details
import com.jordan.watch.logic.DetailsMath
import com.jordan.watch.logic.Resume
import com.jordan.watch.logic.Tile
import com.jordan.watch.storage.Poster
import com.jordan.watch.storage.PosterCache
import com.jordan.watch.storage.PosterSize
import com.jordan.watch.storage.WatchLibrary
import com.jordan.watch.storage.WatchPaths
import com.jordan.watch.ui.ActionRow
import com.jordan.watch.ui.BackTopBar
import com.jordan.watch.ui.CenteredMessage
import com.jordan.watch.ui.HairlineDivider
import com.jordan.watch.ui.LocalAccent
import com.jordan.watch.ui.PosterImage
import com.jordan.watch.ui.ProgressBar
import com.jordan.watch.ui.ThemedScreen
import com.thelightphone.sdk.SealedLightActivity
import com.jordan.watch.ui.VolumeKeyScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A movie's or an episode's page: poster, facts, overview, director, cast and quality line,
 * then Mark watched, Watchlist and (movies) Remove from phone. PLAY / RESUME is at the
 * bottom. The page's accent color comes from the poster (decoration only: the progress bar
 * and a thin rule, both also shown by position and words).
 */
class DetailScreen(sealedActivity: SealedLightActivity, private val id: String) : VolumeKeyScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val state by WatchLibrary.state.collectAsState()
        val positions by WatchLibrary.repositories.playback.positions.collectAsState()
        val watchedMap by WatchLibrary.repositories.playback.watched.collectAsState()
        val watchlist by WatchLibrary.repositories.watchlist.ids.collectAsState()
        val removals by WatchLibrary.repositories.removals.ids.collectAsState()
        val library = state.library
        val details = library.movie(id)?.let(DetailsMath::forMovie)
            ?: library.episode(id)?.let { (show, episode) -> DetailsMath.forEpisode(show, episode) }

        ThemedScreen {
            BackTopBar(details?.title ?: "", onBack = { goBack() })
            if (details == null) {
                CenteredMessage("This title isn't on the phone any more.", Modifier.weight(1f))
                return@ThemedScreen
            }
            val watched = id in watchedMap
            val position = positions[id]
            val accent = rememberPosterAccent(details.poster) ?: LocalAccent.current
            CompositionLocalProvider(LocalAccent provides accent) {
                Box(Modifier.weight(1f)) {
                    LightScrollView(modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
                        Header(details, accent)
                        val ms = position?.positionMs ?: 0
                        if (ms > 0 && !watched && details.runtimeMin != null) {
                            Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                            ProgressBar(Resume.progress(ms, details.runtimeMin * 60_000L))
                        }
                        details.overview?.let { Paragraph(it) }
                        details.director?.let { Paragraph(it, lighten = true) }
                        details.cast?.let { Paragraph(it, lighten = true) }
                        Spacer(Modifier.height(1f.gridUnitsAsDp()))
                        HairlineDivider()
                        ActionRow(if (watched) "Watched · mark unwatched" else "Mark watched") { toggleWatched(watched) }
                        HairlineDivider()
                        ActionRow(if (id in watchlist) "On your Watchlist · remove" else "Add to Watchlist") {
                            WatchLibrary.inBackground { WatchLibrary.repositories.watchlist.toggle(id) }
                        }
                        if (details.isMovie) {
                            HairlineDivider()
                            if (id in removals) {
                                ActionRow("Waiting for the PC to remove it", detail = "Keep") {
                                    WatchLibrary.inBackground { WatchLibrary.repositories.removals.cancel(id) }
                                }
                            } else {
                                ActionRow("Remove from phone") { navigateTo({ RemoveScreen(it, id, details.title) }) { removed -> if (removed) goBack() } }
                            }
                        }
                        Spacer(Modifier.height(1f.gridUnitsAsDp()))
                    }
                }
            }
            val label = DetailsMath.playLabel(position, watched).uppercase()
            LightBottomBar(items = listOf(LightBarButton.Text(text = label, onClick = ::play)))
        }
    }

    /** Poster on the left, title and facts on the right, an accent rule under both. */
    @Composable
    private fun Header(details: Details, accent: Color) {
        Row(Modifier.fillMaxWidth().padding(top = 0.5f.gridUnitsAsDp())) {
            Box(Modifier.width(POSTER_WIDTH.gridUnitsAsDp()).height((POSTER_WIDTH * 1.5f).gridUnitsAsDp())) {
                PosterImage(Tile(details.id, details.title, "", details.poster), Modifier.fillMaxSize(), PosterSize.DETAIL)
            }
            Spacer(Modifier.width(1f.gridUnitsAsDp()))
            Column(Modifier.weight(1f)) {
                LightText(text = details.title, variant = LightTextVariant.Subheading, maxLines = 2, overflow = TextOverflow.Ellipsis)
                details.facts?.let { Line(it) }
                details.genres?.let { Line(it, lighten = true) }
                details.quality?.let { Line(it, lighten = true) }
            }
        }
        Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
        Box(Modifier.fillMaxWidth().height(ACCENT_RULE_PX.designVerticalPxToDp()).background(accent))
    }

    @Composable
    private fun Line(text: String, lighten: Boolean = false) {
        Spacer(Modifier.height(0.4f.gridUnitsAsDp()))
        LightText(text = text, variant = LightTextVariant.Detail, lighten = lighten, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }

    @Composable
    private fun Paragraph(text: String, lighten: Boolean = false) {
        Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
        LightText(text = text, variant = LightTextVariant.Paragraph, lighten = lighten)
    }

    private fun toggleWatched(watched: Boolean) {
        val playback = WatchLibrary.repositories.playback
        WatchLibrary.inBackground {
            if (watched) playback.markUnwatched(id) else playback.markWatched(id, System.currentTimeMillis())
        }
    }

    private fun play() {
        navigateTo({ PlayerScreen(it, id) })
    }

    private companion object {
        const val POSTER_WIDTH = 9f
        const val ACCENT_RULE_PX = 6f
    }
}

/** The poster's accent color, worked out off the main thread; null until then or if it has none. */
@Composable
fun rememberPosterAccent(poster: String?): Color? =
    produceState<Color?>(null, poster) {
        val file = poster?.let { File(WatchPaths.root, it) } ?: return@produceState
        val found = PosterCache.load(file, PosterSize.GRID) as? Poster.Found ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val image = found.image
            val pixels = IntArray(image.width * image.height)
            image.readPixels(pixels)
            AccentColor.pick(pixels)?.let { Color(it) }
        }
    }.value
