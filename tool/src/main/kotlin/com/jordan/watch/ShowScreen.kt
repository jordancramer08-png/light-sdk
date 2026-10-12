package com.jordan.watch

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.jordan.watch.logic.DetailsMath
import com.jordan.watch.logic.Tile
import com.jordan.watch.logic.TitleLists
import com.jordan.watch.storage.PosterSize
import com.jordan.watch.storage.WatchLibrary
import com.jordan.watch.ui.ActionRow
import com.jordan.watch.ui.BackTopBar
import com.jordan.watch.ui.CenteredMessage
import com.jordan.watch.ui.HairlineDivider
import com.jordan.watch.ui.LocalAccent
import com.jordan.watch.ui.PosterImage
import com.jordan.watch.ui.SectionHeading
import com.jordan.watch.ui.ThemedScreen
import com.thelightphone.sdk.SealedLightActivity
import com.jordan.watch.ui.VolumeKeyScreen
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

/** A TV show: poster, facts and overview, Watchlist, then its seasons (with how many are watched). */
class ShowScreen(sealedActivity: SealedLightActivity, private val showId: String) : VolumeKeyScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val state by WatchLibrary.state.collectAsState()
        val watched by WatchLibrary.repositories.playback.watched.collectAsState()
        val watchlist by WatchLibrary.repositories.watchlist.ids.collectAsState()
        val show = state.library.show(showId)
        ThemedScreen {
            BackTopBar(show?.show?.title ?: "", onBack = { goBack() })
            if (show == null) {
                CenteredMessage("This show isn't on the phone any more.", Modifier.weight(1f))
                return@ThemedScreen
            }
            val s = show.show
            val poster = s.poster?.let { "${show.folder}/$it" }
            val accent = rememberPosterAccent(poster) ?: LocalAccent.current
            CompositionLocalProvider(LocalAccent provides accent) {
                LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
                    Row(Modifier.fillMaxWidth().padding(top = 0.5f.gridUnitsAsDp())) {
                        Box(Modifier.width(7f.gridUnitsAsDp()).height(10.5f.gridUnitsAsDp())) {
                            PosterImage(Tile(s.id, s.title, "", poster), Modifier.fillMaxSize(), PosterSize.DETAIL)
                        }
                        Spacer(Modifier.width(1f.gridUnitsAsDp()))
                        Column(Modifier.weight(1f)) {
                            LightText(text = s.title, variant = LightTextVariant.Subheading, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val facts = listOfNotNull(s.year?.toString(), s.rating?.let(DetailsMath::stars), s.contentRating).joinToString(" · ")
                            if (facts.isNotEmpty()) LightText(text = facts, variant = LightTextVariant.Detail, maxLines = 1)
                            if (s.genres.isNotEmpty()) {
                                LightText(text = s.genres.joinToString(", "), variant = LightTextVariant.Detail, lighten = true, maxLines = 2)
                            }
                        }
                    }
                    if (s.overview.isNotBlank()) {
                        Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                        LightText(text = s.overview, variant = LightTextVariant.Paragraph, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                    HairlineDivider()
                    ActionRow(if (showId in watchlist) "On your Watchlist · remove" else "Add to Watchlist") {
                        WatchLibrary.inBackground { WatchLibrary.repositories.watchlist.toggle(showId) }
                    }
                    SectionHeading("Seasons")
                    val seasons = TitleLists.seasons(state.library, show, watched)
                    seasons.forEachIndexed { i, row ->
                        ActionRow(row.label, detail = row.detail) { navigateTo({ SeasonScreen(it, showId, row.season) }) }
                        if (i != seasons.lastIndex) HairlineDivider()
                    }
                    Spacer(Modifier.height(1f.gridUnitsAsDp()))
                }
            }
        }
    }
}
