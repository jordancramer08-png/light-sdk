package com.thelightphone.listen.podcasts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.listen.podcasts.feed.Episode
import com.thelightphone.listen.podcasts.store.EpisodeState
import com.thelightphone.listen.ui.OneLine
import com.thelightphone.listen.ui.ThemedScreen
import com.thelightphone.listen.ui.UniformRow
import com.thelightphone.listen.ui.VolumeKeyScreen
import com.thelightphone.listen.ui.tapOrHold
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/** Episode rows on a show page (grid units, divider included): title, then date · length · progress. */
const val EPISODE_ROW_GRID_UNITS = 5f

/**
 * One episode in a list: its title (lighter once played), the date, length and progress
 * under it, and a downloaded mark at the right. Tap to open it; press and hold to mark it
 * played or unplayed.
 */
@Composable
fun EpisodeRow(
    episode: Episode,
    state: EpisodeState,
    showDivider: Boolean,
    onOpen: () -> Unit,
    onHold: () -> Unit,
) {
    UniformRow(
        heightGridUnits = EPISODE_ROW_GRID_UNITS,
        showDivider = showDivider,
        modifier = Modifier.tapOrHold(onHold = onHold, onClick = onOpen),
    ) {
        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                OneLine(text = episode.title, variant = LightTextVariant.Copy, lighten = state.played)
                OneLine(text = episodeDetail(episode, state), variant = LightTextVariant.Detail, lighten = true)
            }
            if (state.download != null) {
                LightIcon(
                    icon = LightIcons.DOWNLOADED_ARROW,
                    size = 1.5f,
                    contentDescription = "Downloaded",
                    modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
                )
            }
        }
    }
}

/** What to do when unfollowing a show. */
enum class UnfollowChoice { UNFOLLOW, UNFOLLOW_AND_DELETE }

/**
 * "Unfollow this show?" When it has downloaded episodes, asks whether to delete them too.
 * Back hands back nothing (nothing changes).
 */
class UnfollowScreen(
    sealedActivity: SealedLightActivity,
    private val showTitle: String,
    private val downloads: Int,
) : VolumeKeyScreen<UnfollowChoice>(sealedActivity) {

    @Composable
    override fun Content() {
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(showTitle),
            )
            val question = if (downloads == 0) {
                "Unfollow this show?"
            } else {
                "Unfollow this show?\n\nYou have ${if (downloads == 1) "1 downloaded episode" else "$downloads downloaded episodes"} " +
                    "from it. Delete ${if (downloads == 1) "it" else "them"} too?"
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                LightText(text = question, variant = LightTextVariant.Copy, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            LightBottomBar(
                items = if (downloads == 0) {
                    listOf(LightBarButton.Text(text = "UNFOLLOW", onClick = { goBack(UnfollowChoice.UNFOLLOW) }))
                } else {
                    listOf(
                        LightBarButton.Text(text = "DELETE", onClick = { goBack(UnfollowChoice.UNFOLLOW_AND_DELETE) }),
                        LightBarButton.Text(text = "KEEP", onClick = { goBack(UnfollowChoice.UNFOLLOW) }),
                    )
                },
            )
        }
    }
}
