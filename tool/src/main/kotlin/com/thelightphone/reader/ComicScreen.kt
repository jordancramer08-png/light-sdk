package com.thelightphone.reader

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.thelightphone.reader.comics.comicSlug
import com.thelightphone.reader.data.ComicMeta
import com.thelightphone.reader.data.ComicPositionRepository
import com.thelightphone.reader.data.DatabaseQueue
import com.thelightphone.reader.data.ReadingStatusRepository
import com.thelightphone.reader.data.readerDatabase
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Where a comic opens. The page viewer comes in the next update; for now this says so, and
 * its bottom bar reaches the comic's lists and details. Opening it counts as opening the
 * comic: a Want to Read comic becomes Reading, and its saved place (page 1 the first time)
 * is stamped now, so it becomes the Continue reading comic.
 */
class ComicScreen(
    sealedActivity: SealedLightActivity,
    private val meta: ComicMeta,
    private val title: String,
) : SimpleLightScreen<Unit>(sealedActivity) {

    private val slug = comicSlug(meta.path)

    init {
        val positions = ComicPositionRepository.getInstance { lightContext.readerDatabase() }
        val statuses = ReadingStatusRepository.getInstance { lightContext.readerDatabase() }
        DatabaseQueue.write {
            positions.markOpened(slug)
            statuses.markOpened(slug)
        }
    }

    @Composable
    override fun Content() {
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(title),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            CenteredMessage(
                "The comic viewer comes in the next update.\n\n${comicPagesText(meta)}",
                modifier = Modifier.weight(1f),
            )
            LightBottomBar(
                items = listOf(
                    LightBarButton.Text(text = "ADD TO LIST", onClick = ::openAddToList),
                    LightBarButton.Text(text = "DETAILS", onClick = ::openDetails),
                ),
            )
        }
    }

    private fun openAddToList() {
        navigateTo(screenFactory = { AddToListScreen(it, slug) })
    }

    private fun openDetails() {
        navigateTo(screenFactory = { ComicDetailsScreen(it, meta, title) })
    }
}
